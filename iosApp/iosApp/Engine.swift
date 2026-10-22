import Foundation
import UserNotifications
import WidgetKit
#if canImport(AlarmKit)
import AlarmKit
#endif

/// 鳴動エンジン。Android 側 AlarmRepository.resync と同じ責務:
/// カレンダー読込 → shared 展開 → 差分プラン → 予約/取消/即時鳴動/期限切れ処理。
/// AlarmKit の約64件上限に収めるため、未来インスタンスは時刻順に capacity まで予約する。
@MainActor
final class Engine: ObservableObject {

    let store: Store
    let reader = EventKitReader()
    private let scheduler: AlarmScheduler

    /// 展開ホライズン (Android と同じ 14 日)
    private let horizonDays = 14

    /// 鳴動中のインスタンス id (RingingView 用)
    @Published var alertingIds: Set<String> = []

    /// OS へ予約を渡している最中の取り込み音 (名前 → 本数)。予約が記録に載る前に
    /// 音の片付けが走っても、これから鳴る音を消さないために数える。
    /// バックグラウンドの resync は別の Engine で走るので、エンジンごとでなくアプリ全体で数える
    private static var inFlightSounds: [String: Int] = [:]

    /// 予約を書き換える処理 (resync・音の入れ直し) を1本ずつ流すための列の末尾。
    /// どちらも途中で await するので、並んで走ると片方が古い写しで相手の予約を上書きする
    private var reservationQueueTail: Task<Void, Never>?

    /// 記録だけ先に置いて、OS への予約を列で待っているスヌーズの子の id。
    /// 待っている間に走った resync は、この子を計画に入れていない (計画を作った後に置かれた) ので、
    /// 記録を外すことがある。外れても予約の側が取り直せるよう、印は記録と別に持つ
    private var snoozeChildrenAwaitingBooking: Set<String> = []
    /// 予約を待つ間に取り消された子の id。予約の側はこれを見て、書き戻さずに終わる
    private var cancelledAwaitingChildren: Set<String> = []

    /// [scheduler] [store] はテストで OS の予約失敗を再現し、端末の保存データを触らないための差し替え口。
    /// アプリは渡さない
    init(scheduler: AlarmScheduler? = nil, store: Store? = nil) {
        self.store = store ?? Store()
        if let scheduler {
            self.scheduler = scheduler
        } else if #available(iOS 26.0, *) {
            self.scheduler = AlarmKitScheduler()
        } else {
            self.scheduler = NotificationScheduler()
        }
        observeAlarmUpdates()
    }

    var usesRealAlarms: Bool { scheduler.usesRealAlarms }
    var schedulerAuthorized: Bool { scheduler.authorizationGranted }
    var schedulerUndetermined: Bool { scheduler.authorizationUndetermined }

    /// アラーム (AlarmKit、iOS 26 未満は通知) の許可だけを頼む。権限画面が1項目ずつ頼むのに使う
    func requestSchedulerAuthorization() async {
        await scheduler.requestAuthorization()
    }

    func authorizeScheduler() async {
        await scheduler.requestAuthorization()
        // MISSED 通知は AlarmKit とは別に UN の許可が要る。求めないと権限自体が
        // 永久に notDetermined のまま通知が一度も出ない。
        _ = try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])
    }

    // MARK: - AlarmKit 状態観測

    /// alarmUpdates を拾ってストアに反映する:
    /// alerting → FIRED / 消滅(ユーザーがアラートで停止) → DISMISSED / countdown → SNOOZED。
    private func observeAlarmUpdates() {
        guard #available(iOS 26.0, *), let ak = scheduler as? AlarmKitScheduler else { return }
        Task { [weak self] in
            for await _ in ak.updates {
                guard let self else { continue }
                await self.reconcileAlarmKitState()
            }
        }
    }

    @available(iOS 26.0, *)
    private func reconcileAlarmKitState() {
        guard let ak = scheduler as? AlarmKitScheduler else { return }
        // UUID→instanceId は全予約行を決定的 UUID で逆引き
        let live = Dictionary(uniqueKeysWithValues: ak.allAlarms.map { ($0.id, $0.state) })
        for (id, rec) in store.state.scheduled {
            let uuid = AlarmKitScheduler.uuid(from: id)
            guard let state = live[uuid] else {
                // AlarmKit から消えた = ユーザーがアラートUIで完全に止めた
                if rec.state == .fired || rec.state == .snoozed {
                    store.markState(id, .dismissed)
                    // スヌーズ子が pending で残っている限り単発は消費扱いにしない。
                    // ここで親を OFF にすると子の温存条件 (sa: 親 enabled) を壊し、
                    // 次回 resync で子が捨てられてスヌーズが鳴らなくなる。
                    let snoozeChildPending = rec.state == .snoozed
                        && Self.hasPendingSnoozeChild(of: id, in: store.state.scheduled)
                    if let aid = rec.instance.standaloneAlarmId, !snoozeChildPending {
                        disableConsumedOneShots([aid])
                    }
                    alertingIds.remove(id)
                }
                continue
            }
            switch state {
            case .alerting:
                if rec.state != .fired {
                    store.markState(id, .fired)
                    store.audit("FIRED", "\(rec.instance.title) (alerting)")
                }
                alertingIds.insert(id)
            case .countdown:
                // postAlert カウントダウン中 = ユーザーがアラートUIでスヌーズを押した
                if rec.state == .fired {
                    store.markState(id, .snoozed)
                    store.audit("SNOOZE", "\(id) (native)")
                }
                alertingIds.remove(id)
            default:
                break
            }
        }
    }

    // MARK: - resync (中核)

    /// イベント/単発アラームを展開し、予約との差分を適用する。
    /// Android 版 resync の意味論を踏襲:
    /// - カレンダー読み取り失敗時は既存予約を維持 (「鳴らない」に倒さない)
    /// - PENDING のスヌーズ子インスタンスは desired に併合して残す
    /// - グレース幅を過ぎた PENDING は MISSED + 通知
    func resync(reason: String) async {
        await runExclusively { await self.resyncNow(reason: reason) }
    }

    private func runExclusively(_ body: @escaping @MainActor () async -> Void) async {
        let previous = reservationQueueTail
        let task = Task { @MainActor in
            await previous?.value
            await body()
        }
        reservationQueueTail = task
        await task.value
    }

    private func resyncNow(reason: String) async {
        let now = Self.nowMillis
        let horizon = now + Int64(horizonDays) * 86_400_000
        let zone = TimeZone.current.identifier

        // カレンダー読込 (権限無しでも既存予約は消さない: disabled 展開で desired から外すだけ)
        var events: [CalendarEventDTO] = []
        var calendarsOk = false
        if reader.authorizationGranted {
            // 過去側はグレース幅でなく14日に広げる (Android と同じ)。スヌーズ連鎖が
            // 親予定の開始から長く伸びても liveEventKeys が親を見失わない。
            events = reader.events(
                from: Date(timeIntervalSince1970: TimeInterval(now) / 1000 - Double(horizonDays) * 86_400),
                to: Date(timeIntervalSince1970: TimeInterval(horizon) / 1000))
            store.setCalendars(reader.calendars())
            store.setLastEvents(events)
            calendarsOk = true
        } else {
            store.audit("CAL_SKIP", "permission not granted (reason=\(reason))")
        }
        var s = store.state

        // 期限切れ PENDING → MISSED + 通知。Android 同様 resync の先頭で処理する:
        // 終端判定・preserved ・プランは全てこれ以降の状態を見る (逆順だと
        // MISSED 化した行が preserved 経由で desired に復活して鳴り直す)。
        let graceMillis = Int64(60 * 60 * 1000)
        var missedAlarmIds: Set<Int64> = []
        for rec in s.scheduled.values where rec.state == .pending && rec.instance.triggerAtMillis < now - graceMillis {
            store.markState(rec.instance.id, .missed)
            store.audit("MISSED", "\(rec.instance.title) @\(Self.hm(rec.instance.triggerAtMillis))")
            notifyMissed(rec.instance)
            if let aid = rec.instance.standaloneAlarmId { missedAlarmIds.insert(aid) }
        }
        disableConsumedOneShots(missedAlarmIds)
        s = store.state

        // 終端行は先に読む。同 id の蘇生防止と単発アラームの消費判定の両方で使う
        // (Android AlarmRepository.resync と同じ意味論)。
        let terminalIds = Set(s.scheduled.values
            .filter { [.fired, .dismissed, .snoozed, .missed].contains($0.state) }
            .map { $0.instance.id })
        // 単発 (daysOfWeek 空) は一度終端に達したら二度と発行しない。
        // 展開は毎回「次の in-window 日付」へ同 id/新 id を発行するので、終端行が
        // 残る限り「1回のみ」の約束が破られて鳴り続ける。
        let consumedAlarmIds = Set(s.scheduled.values
            .filter { terminalIds.contains($0.instance.id) }
            .compactMap { $0.instance.standaloneAlarmId })

        // アカウント単位の設定 (#74) を当てはめたルール。展開とスヌーズ親の判定で同じものを使う
        let effectiveRules = store.effectiveCalendarRules()
        let expandReq = ExpandRequest(
            events: events,
            standalone: s.standaloneAlarms.filter {
                $0.effectiveRepeatMode != "ONCE" || !consumedAlarmIds.contains($0.id)
            },
            calendarRules: effectiveRules,
            overrides: s.overrides,
            disabledCalendarIds: [],
            nowMillis: now,
            horizonMillis: horizon,
            zoneId: zone,
            defaultMinutesBefore: s.defaultMinutesBefore,
            defaultSnoozeMinutes: s.defaultSnoozeMinutes,
            standaloneDays: horizonDays,
            defaultStartAction: s.defaultStartAction,
            defaultReminderAction: s.defaultReminderAction,
            titleCodes: s.titleCodes,
            inviteFilter: s.inviteFilter,
            eventTypeFilter: s.eventTypeFilter,
            importEventReminders: s.importEventReminders,
            muteAll: s.muteAll
        )
        guard var desired = SharedDomain.expand(expandReq)?.instances else {
            store.audit("RESYNC_FAIL", "shared expand returned nil")
            return
        }
        // 終了済み状態 (鳴動/停止/スヌーズ/見逃し) の同 id は蘇生させない。
        // 展開が同 id を再発行しても、ユーザーが「止めた」「スヌーズした」意味を守る。
        // ただし NOTIFY 配信の記録は予約時点で .fired 化されるため、展開が再発行
        // した未配信分 (dropped 側) は後段の notify 照合で「残す予約」に含める必要がある。
        let dropped = desired.filter { terminalIds.contains($0.id) }
        desired.removeAll { terminalIds.contains($0.id) }

        // スヌーズ中インスタンスの親がまだ「鳴らすべき」かの判定に使う。
        // 読み取り失敗時は空のまま (EVENT 温存経路で保護される)。
        let liveEventKeys: Set<String> = calendarsOk ? Set(events.filter {
            s.overrides[$0.instanceKey]?.action != EventAction.mute.rawValue &&
            (effectiveRules[$0.calendarId]?.enabled ?? true)
        }.map { $0.instanceKey }) : []

        // 展開で再生成されない PENDING 系を理想状態へ持ち越す:
        // - タイマー (常に)
        // - スヌーズ子 (親が今も鳴動意思を持つ場合だけ — 親アラームの無効化・削除・
        //   予定ミュート後にスヌーズだけ鳴るのを防ぐ。親の照合はグレース窓を跨ぐ
        //   スヌーズが誤殺されないよう、sa: はアラーム有効性、ev: は予定の存続を見る)
        // - カレンダー読めない時のイベント由来 pending (読み取り失敗で消さない)
        let desiredIds = Set(desired.map { $0.id })
        let preserved = s.scheduled.values.filter { $0.state == .pending }.map { $0.instance }.filter { inst in
            guard !desiredIds.contains(inst.id) else { return false }
            if inst.kind == "TIMER" { return true }
            if inst.snoozeSeq > 0 {
                let root = inst.id.components(separatedBy: ":snz").first ?? inst.id
                if root.hasPrefix("sa:") {
                    let aid = Int64(root.dropFirst(3).split(separator: ":").first.map(String.init) ?? "")
                    return s.standaloneAlarms.contains { $0.id == aid && $0.enabled }
                }
                if root.hasPrefix("ev:") {
                    let key = root.dropFirst(3).components(separatedBy: ":b").first ?? ""
                    return !calendarsOk || desiredIds.contains(root) || liveEventKeys.contains(key)
                }
                return !calendarsOk && inst.kind == "EVENT"
            }
            return !calendarsOk && inst.kind == "EVENT"
        }
        desired += preserved

        // NOTIFY 用 UN 通知の照合: desired に残らない予約 (ミュート/削除/時刻変更)
        // は取り消す。これをしないと鳴るべきでない通知が残って実際に鳴る
        // dropped 側 (.fired 記録化で desired から落ちた未来の未配信 notify) も
        // 残す対象に含める — 含めないと予約済み通知が次回 resync で必ず消され
        // 「鳴るはずの通知が鳴らない」になる
        let notifyKeep = Set((desired + dropped).filter {
            $0.delivery == EventAction.notify.rawValue
        }.map { "notify-\($0.id)" })
        let center = UNUserNotificationCenter.current()
        let pendingReqs = await center.pendingNotificationRequests()
        let staleReqIds = pendingReqs.filter {
            $0.identifier.hasPrefix("notify-") && !notifyKeep.contains($0.identifier)
        }.map { $0.identifier }
        if !staleReqIds.isEmpty {
            center.removePendingNotificationRequests(withIdentifiers: staleReqIds)
            store.audit("NOTIFY_CANCEL", "\(staleReqIds.count)件")
        }

        // OS 側で予約だけが消えた PENDING 行を拾い直す (再起動や OS 内部の破棄でストア上は PENDING だが
        // 実体は無い → 二度と鳴らない穴を塞ぐ。Android は毎回全件 AlarmManager へ再主張する方式で同じ問題を潰している)。
        // 記録は外さず、プランの入力から外して取り直させる: 取り直しに失敗しても、展開で作り直せないタイマーと
        // スヌーズ子の記録が残り、次の resync でまた試せる (外すと、権限が戻っても拾う相手が居ない)
        var unreserved: Set<String> = []
        if let lost = await scheduler.unreservedPendingIds(store.state.scheduled) {
            unreserved = lost
        } else if !scheduler.usesRealAlarms {
            // 通知の予約 (iOS 26 未満) も同じ。鳴る前なのに予約が無い行だけを見る
            for (id, rec) in store.state.scheduled
            where rec.state == .pending && rec.instance.delivery != EventAction.notify.rawValue
                && rec.instance.triggerAtMillis > now {
                if await !scheduler.hasReservation(instanceId: id) { unreserved.insert(id) }
            }
        }
        // 予約が無く、もう鳴らす理由も無い行 (予定を消した等) は、取り消す相手が居ないのでここで外す
        let desiredNow = Set(desired.map { $0.id })
        for id in unreserved where !desiredNow.contains(id) {
            store.removeScheduled(id)
            unreserved.remove(id)
        }
        if !unreserved.isEmpty { store.audit("RECOVER", "予約喪失 \(unreserved.count)件→再予約") }

        let plan = SharedDomain.plan(PlanRequest(
            desired: desired,
            scheduled: store.pendingScheduled().filter { !unreserved.contains($0.key) },
            nowMillis: now
        )) ?? PlanResult(toSchedule: desired, toCancel: [], toFireNow: [])

        // 鳴動中のアラームは plan に関係なく絶対にキャンセルしない。
        // (resync 実行中に鳴り始めたアラームを差分が潰して「押してないのに
        //   止まった」事象を防ぐための防衛線)
        var alertingUuids = Set<UUID>()
        if #available(iOS 26.0, *), let ak = scheduler as? AlarmKitScheduler {
            alertingUuids = Set(ak.alertingAlarms.map { $0.id })
        }
        for id in plan.toCancel {
            var alertingOnOS = alertingIds.contains(id)
            if #available(iOS 26.0, *) {
                alertingOnOS = alertingOnOS || alertingUuids.contains(AlarmKitScheduler.uuid(from: id))
            }
            if alertingOnOS {
                store.audit("CANCEL_SKIP", "\(id): 鳴動中のため差分キャンセルを抑止")
                continue
            }
            await scheduler.cancel(instanceId: id, reservationId: store.state.scheduled[id]?.alarmKitId)
            store.removeScheduled(id)
        }

        // toFireNow (過去だがグレース内): AlarmKit は過去日時を予約できないので、
        // 記録は元時刻のまま・OS予約だけ最速の未来時刻にずらして実際に鳴らす。
        // fired 済み扱いで UI だけ出すと、アプリ非前面では音も通知も出ず無言消費になる。
        let fireNowIds = Set(plan.toFireNow.map { $0.id })
        // 予約カウントは既存の AlarmKit 予約 (toCancel 消化後) から始める。
        // 新規分だけ数えると温存分と合算で上限を超えて SCHEDULE_ERR になる。
        var scheduledCount = 0
        if #available(iOS 26.0, *), let ak = scheduler as? AlarmKitScheduler {
            scheduledCount = ak.allAlarms.count
        }
        for inst in plan.toFireNow {
            // NOTIFY 配信はアラーム予約ではなく即時通知に倒す
            if inst.delivery == EventAction.notify.rawValue {
                deliverNotifyNow(inst)
                continue
            }
            let shifted = AlarmInstanceDTO(
                id: inst.id,
                triggerAtMillis: now + 5_000,
                title: inst.title,
                kind: inst.kind,
                eventId: inst.eventId,
                standaloneAlarmId: inst.standaloneAlarmId,
                snoozeMinutes: inst.snoozeMinutes,
                soundUri: inst.soundUri,
                minutesBefore: inst.minutesBefore,
                eventStartMillis: inst.eventStartMillis,
                snoozeSeq: inst.snoozeSeq,
                delivery: inst.delivery
            )
            do {
                let r = try await reserve(shifted)
                store.putScheduled(ScheduledRecord(instance: inst, state: .pending, alarmKitId: r.id, soundFile: r.sound))
                scheduledCount += 1
            } catch {
                store.audit("SCHEDULE_ERR", "\(inst.id): \(error.localizedDescription)")
            }
        }

        // 未来分を時刻順に capacity まで予約 (スヌーズ子を最優先)
        let future = plan.toSchedule
            .filter { !fireNowIds.contains($0.id) && $0.triggerAtMillis > now }
            .sorted { ($0.snoozeSeq > 0 ? Int64.min : $0.triggerAtMillis) < ($1.snoozeSeq > 0 ? Int64.min : $1.triggerAtMillis) }
        for inst in future {
            if inst.delivery == EventAction.notify.rawValue {
                // NOTIFY は AlarmKit 枠を消費せず UN 通知として時刻指定で予約する。
                // 発火時にアプリ側のコールバックは要らない — 記録は発火済み扱いで残し、
                // 展開が同 id を再生しても terminalIds が蘇生を防ぐ。
                scheduleEventNotification(inst, atMillis: inst.triggerAtMillis)
                store.putScheduled(ScheduledRecord(instance: inst, state: .fired))
                store.audit("NOTIFY_SCHED", "\(inst.title) @\(Self.hm(inst.triggerAtMillis))")
                continue
            }
            if scheduledCount >= capacity {
                store.audit("CAPACITY_SKIP", "\(inst.title)")
                continue
            }
            do {
                let r = try await reserve(inst)
                store.putScheduled(ScheduledRecord(instance: inst, state: .pending, alarmKitId: r.id, soundFile: r.sound))
                scheduledCount += 1
            } catch {
                store.audit("SCHEDULE_ERR", "\(inst.id): \(error.localizedDescription)")
            }
        }

        store.pruneOldInstances(nowMillis: now)
        // 止めた・見逃したで鳴る予約が無くなった音はここで片付く
        removeUnusedSounds()
        store.writeWidgetSnapshot()
        WidgetCenter.shared.reloadAllTimelines()
        store.audit("RESYNC", "\(reason): desired=\(desired.count) sched=\(plan.toSchedule.count) cancel=\(plan.toCancel.count)")
    }

    private var capacity: Int {
        if #available(iOS 26.0, *), scheduler is AlarmKitScheduler { return AlarmKitScheduler.maxCapacity }
        return NotificationScheduler.maxCapacity
    }

    // MARK: - 鳴動/停止/スヌーズ

    /// 単発アラームの終端到達をアラーム本体の OFF に反映する (Android 側と同じ)。
    /// 「1回のみ」は鳴らし切った/見逃した時点で消費済み。再有効化は消費リセットで復活できる。
    private func disableConsumedOneShots(_ alarmIds: Set<Int64>) {
        for aid in alarmIds {
            guard let a = store.state.standaloneAlarms.first(where: { $0.id == aid }),
                  a.effectiveRepeatMode == "ONCE", a.enabled else { continue }
            store.setAlarmEnabled(id: aid, false)
            store.audit("ALARM_OFF", "単発アラーム消費で停止: \(a.label.isEmpty ? "アラーム" : a.label) (id=\(aid))")
        }
    }

    /// 停止: AlarmKit の実状態を見て止め切る。
    /// 以前は `try? stop()` の結果を見ず UI だけ閉じていたため、停止が失敗すると
    /// 「画面は全部閉じたのに音だけ鳴り続け、止める術が無い」事故になった。
    /// ここでは OS 側が静かになるまでエスカレートし、失敗時は鳴動画面を残す。
    func dismiss(_ inst: AlarmInstanceDTO) {
        if #available(iOS 26.0, *), let ak = scheduler as? AlarmKitScheduler,
           !ak.silence(uuid: AlarmKitScheduler.uuid(from: inst.id)) {
            // stop→cancel どちらを投げても鳴動継続: UI を消すと止める術が無くなるので
            // 鳴動画面を残し、ユーザーに再度止めさせられる状態を維持する。
            store.audit("ERROR", "停止失敗(鳴動継続): \(inst.id)")
            return
        }
        alertingIds.remove(inst.id)
        store.markState(inst.id, .dismissed)
        if let aid = inst.standaloneAlarmId { disableConsumedOneShots([aid]) }
        store.audit("DISMISS", inst.title)
    }

    /// スヌーズ: AlarmKit なら countdown() でネイティブスヌーズ (postAlert 後に再アラート)。
    /// countdown が失敗した時は子インスタンス予約に落ちる (黙って消さない)。
    /// 通知経路は snoozeSeq+1 の子インスタンスを新規予約 (Android の snoozed() と同じ id 規則)。
    /// 戻り値は子の予約を待つための Task (テストが予約の完了を待つ)。ネイティブスヌーズと失敗時は nil
    @discardableResult
    func snooze(_ inst: AlarmInstanceDTO) -> Task<Void, Never>? {
        if #available(iOS 26.0, *), let ak = scheduler as? AlarmKitScheduler,
           (try? ak.countdown(uuid: AlarmKitScheduler.uuid(from: inst.id))) != nil {
            alertingIds.remove(inst.id)
            store.markState(inst.id, .snoozed)
            store.audit("SNOOZE", "\(inst.id) +\(inst.snoozeMinutes)m (native)")
            return nil
        }
        return scheduleSnoozeChild(inst, minutes: inst.snoozeMinutes)
    }

    /// プリセット分数のスヌーズ (鳴動画面の「スヌーズ時間を選ぶ」チップ)。
    /// AlarmKit の countdown は予約時の分数固定で上書きできないため、
    /// 既定と異なる分数は常に子インスタンス予約で実現する (Android の
    /// ACTION_SNOOZE + EXTRA_SNOOZE_MINUTES と同じく子の snoozeMinutes も上書きする)。
    @discardableResult
    func snooze(_ inst: AlarmInstanceDTO, minutes: Int) -> Task<Void, Never>? {
        if minutes == inst.snoozeMinutes {
            return snooze(inst)
        }
        return scheduleSnoozeChild(inst, minutes: minutes)
    }

    /// [id] のスヌーズ子 (id:snz1, id:snz2 …) がまだ鳴る前 (pending) で残っているか
    static func hasPendingSnoozeChild(of id: String, in scheduled: [String: ScheduledRecord]) -> Bool {
        scheduled.contains { $0.key.hasPrefix("\(id):snz") && $0.value.state == .pending }
    }

    /// 子インスタンス予約によるスヌーズ本体。まだ鳴り続けているのに UI だけ閉じると
    /// 二重鳴動の元を残すので、子予約の前に元を静かにしておく (止められないなら子も足さない)。
    private func scheduleSnoozeChild(_ inst: AlarmInstanceDTO, minutes: Int) -> Task<Void, Never>? {
        if #available(iOS 26.0, *), let ak = scheduler as? AlarmKitScheduler,
           !ak.silence(uuid: AlarmKitScheduler.uuid(from: inst.id)) {
            store.audit("ERROR", "スヌーズ失敗(元アラーム鳴動継続): \(inst.id)")
            return nil
        }
        alertingIds.remove(inst.id)
        let next = AlarmInstanceDTO(
            id: "\(inst.id):snz\(inst.snoozeSeq + 1)",
            triggerAtMillis: Self.nowMillis + Int64(minutes) * 60_000,
            title: inst.title,
            kind: inst.kind,
            eventId: inst.eventId,
            standaloneAlarmId: inst.standaloneAlarmId,
            snoozeMinutes: minutes,
            soundUri: inst.soundUri,
            minutesBefore: inst.minutesBefore,
            eventStartMillis: inst.eventStartMillis,
            snoozeSeq: inst.snoozeSeq + 1
        )
        store.markState(inst.id, .snoozed)
        // 子レコードは await を挟まずここで先行登録する。親の AlarmKit 予約を
        // 止めた直後に reconcile が走ると「snoozed + AlarmKit 消滅 + 子なし」と
        // 誤判定されて単発親が消費OFFになり、登録済みなら温存条件を保てる。
        store.putScheduled(ScheduledRecord(instance: next, state: .pending, alarmKitId: nil))
        snoozeChildrenAwaitingBooking.insert(next.id)
        cancelledAwaitingChildren.remove(next.id)
        return Task {
            // 取り消し (cancelInstance) と同じ列で予約する。列の外だと、予約の await の間に取り消された子を
            // このあとの putScheduled が書き戻し、取り消したタイマーが鳴る
            await self.runExclusively {
                defer {
                    self.snoozeChildrenAwaitingBooking.remove(next.id)
                    self.cancelledAwaitingChildren.remove(next.id)
                }
                // 列で先に取り消されていたら、予約も書き戻しもしない。
                // 記録の有無では決めない: 待つ間に走った resync が記録を外していても、取り消されていない子は取り直す
                if self.cancelledAwaitingChildren.contains(next.id) {
                    self.store.audit("SNOOZE_SKIP", "\(next.id): 予約の前に取り消された")
                    return
                }
                // 予約失敗 (権限剥奪等) でも記録は .pending で残す — 次回 resync の
                // 温存経路で再予約され権限が戻れば自律回復する。ただし失敗は監査に残す
                // (try? で握り潰すとゾンビ予約がサイレントのまま二度と鳴らない)。
                var rid: String? = nil
                var sound: String? = nil
                do {
                    let r = try await self.reserve(next)
                    rid = r.id
                    sound = r.sound
                    self.store.audit("SNOOZE", "\(inst.id) +\(minutes)m → \(next.id)")
                } catch {
                    self.store.audit("SCHEDULE_ERR", "snooze \(next.id): \(error.localizedDescription)")
                }
                self.store.putScheduled(ScheduledRecord(instance: next, state: .pending, alarmKitId: rid, soundFile: sound))
            }
        }
    }

    // MARK: - タイマー

    func scheduleTimer(durationMillis: Int64, label: String) async {
        let inst = AlarmInstanceDTO(
            id: "tm:\(Self.nowMillis)",
            triggerAtMillis: Self.nowMillis + durationMillis,
            title: label.isEmpty ? "タイマー" : label,
            kind: "TIMER"
        )
        do {
            let r = try await reserve(inst)
            store.putScheduled(ScheduledRecord(instance: inst, state: .pending, alarmKitId: r.id, soundFile: r.sound))
            store.audit("TIMER_SET", "\(durationMillis / 1000)s")
        } catch {
            store.audit("SCHEDULE_ERR", "timer: \(error.localizedDescription)")
        }
    }

    /// 音の入れ直しと同じ列で取り消す。列の外だと、入れ直しの途中で取り消したタイマーを入れ直しが書き戻して鳴らす
    func cancelInstance(_ id: String) async {
        await runExclusively {
            if self.snoozeChildrenAwaitingBooking.contains(id) { self.cancelledAwaitingChildren.insert(id) }
            await self.scheduler.cancel(instanceId: id, reservationId: self.store.state.scheduled[id]?.alarmKitId)
            UNUserNotificationCenter.current()
                .removePendingNotificationRequests(withIdentifiers: ["notify-\(id)"])
            self.store.removeScheduled(id)
        }
    }

    /// 既定のアラーム音を変えた時に、入っている予約を新しい音で入れ直す (#101)。
    /// AlarmKit は音を予約の時に決めるため、入れ直さないと変更前の音で鳴る
    func rescheduleForSoundChange() async {
        await runExclusively { await self.rescheduleForSoundChangeNow() }
    }

    private func rescheduleForSoundChangeNow() async {
        let ids = store.state.scheduled.values
            .filter { needsSoundChange($0) }
            .map { $0.instance.id }
        var count = 0
        var lostIds: [String] = []
        for id in ids {
            // resync とは列で順番に走るが、鳴動の観測は await の間にも記録を変える。
            // ループ前の写しで上書きしないよう、毎回いまの記録を読み直してそれを予約する
            guard let current = store.state.scheduled[id], needsSoundChange(current) else { continue }
            if #available(iOS 26.0, *), let ak = scheduler as? AlarmKitScheduler,
               ak.state(of: AlarmKitScheduler.uuid(from: id)) == .alerting {
                continue
            }
            do {
                let r = try await reserve(current.instance)
                store.putScheduled(ScheduledRecord(instance: current.instance, state: .pending,
                                                   alarmKitId: r.id ?? current.alarmKitId, soundFile: r.sound))
                count += 1
            } catch {
                store.audit("SCHEDULE_ERR", "sound \(id): \(error.localizedDescription)")
                if await !restoreAfterFailedSoundChange(current) { lostIds.append(id) }
            }
        }
        if !lostIds.isEmpty {
            // 記録を外した予約は、ここで予約し直さないとアプリを閉じたまま鳴らない
            await resyncNow(reason: "sound change lost \(lostIds.count)")
        }
        removeUnusedSounds()
        store.audit("SOUND", "\(AlarmSoundLibrary.currentDefault ?? "default"): \(count)件を入れ直し")
    }

    /// 鳴る直前 (5秒以内)・鳴動中・スヌーズ中は入れ直さない。前の音のまま鳴るが、
    /// その音のファイルは記録 (soundFile) が指している間は消さないので既定音には落ちない
    private func needsSoundChange(_ rec: ScheduledRecord) -> Bool {
        rec.state == .pending && rec.instance.triggerAtMillis > Self.nowMillis + 5_000
            && rec.instance.delivery != EventAction.notify.rawValue
            && rec.soundFile != AlarmSoundLibrary.soundName(for: rec.instance)
    }

    /// AlarmKit は予約の前に同じ id の予約を取り消すので、予約が失敗すると前の予約も消えている。
    /// 前の音で予約し直し、それも駄目なら記録を外して一覧に予約ありと出さない
    /// (展開で作り直せないタイマーとスヌーズ子は残して拾い直させる)
    /// @returns false = OS に予約が無いまま (resync で取り直す)
    private func restoreAfterFailedSoundChange(_ rec: ScheduledRecord) async -> Bool {
        let id = rec.instance.id
        if await scheduler.hasReservation(instanceId: id) { return true }
        do {
            let r = try await reserve(rec.instance, sound: rec.soundFile)
            store.putScheduled(ScheduledRecord(instance: rec.instance, state: .pending,
                                               alarmKitId: r.id ?? rec.alarmKitId, soundFile: r.sound))
            store.audit("SOUND_RESTORE", "\(id): 前の音で予約し直し")
            return true
        } catch {
            if !Self.isRegeneratedByExpand(rec.instance) || (rec.instance.kind == "EVENT" && !reader.authorizationGranted) {
                // 展開で作り直せない行と、カレンダーを読めず作り直せない予定の行は、記録を消すと二度と鳴らない。
                // 予約 id を空にして、次の resync で拾い直させる
                store.putScheduled(ScheduledRecord(instance: rec.instance, state: .pending,
                                                   alarmKitId: nil, soundFile: rec.soundFile))
            } else {
                store.removeScheduled(id)
            }
            store.audit("SCHEDULE_ERR", "sound restore \(id): \(error.localizedDescription)")
            return false
        }
    }

    /// 展開 (SharedDomain.expand) が毎回作り直す行か。タイマーとスヌーズ子は今ある記録からしか持ち越されない
    static func isRegeneratedByExpand(_ inst: AlarmInstanceDTO) -> Bool {
        inst.kind != "TIMER" && inst.snoozeSeq == 0
    }

    /// OS へ予約し、その予約に渡した音を一緒に返す。音はここで1回だけ決め、記録に残す
    private func reserve(_ inst: AlarmInstanceDTO) async throws -> (id: String?, sound: String?) {
        try await reserve(inst, sound: AlarmSoundLibrary.soundName(for: inst))
    }

    private func reserve(_ inst: AlarmInstanceDTO, sound: String?) async throws -> (id: String?, sound: String?) {
        if let sound { Self.inFlightSounds[sound, default: 0] += 1 }
        defer {
            if let sound {
                let left = (Self.inFlightSounds[sound] ?? 1) - 1
                Self.inFlightSounds[sound] = left > 0 ? left : nil
            }
        }
        let id = try await scheduler.schedule(inst, sound: sound)
        return (id, sound)
    }

    /// 取り込んだ音のうち、もう鳴らす予定の無いものを消す (#101)。
    /// 待機中・鳴動中・スヌーズ中の記録が指す音、予約を渡している最中の音、既定と個別指定の音は残す。
    /// 入れ直しに失敗して前の音で予約し直した記録も前の音を指すので残る。
    /// バックグラウンドの resync は前景と別の Engine・Store で走り、手元の写しは古いことがある。
    /// 写しだけで決めると前景で取り込んだばかりの音を消すので、保存済みの状態が指す音と、
    /// 写しを読んだ後に置かれたファイルも残す
    func removeUnusedSounds() {
        var keep = Self.soundsInUse(store.state)
        if let saved = store.savedState() {
            keep.formUnion(Self.soundsInUse(saved))
        } else if store.hasSavedFile {
            // 保存済みの状態があるのに読めない時は、別の Engine が書いた分を確かめられない。消さずに次の機会へ回す
            return
        }
        keep.formUnion(Self.inFlightSounds.keys)
        AlarmSoundLibrary.removeAll(except: keep, createdBefore: store.loadedAt)
    }

    private static func soundsInUse(_ s: Store.Persisted) -> Set<String> {
        var keep = Set(s.scheduled.values
            .filter { [.pending, .fired, .snoozed].contains($0.state) }
            .compactMap { $0.soundFile })
        if let current = s.alarmSoundFile { keep.insert(current) }
        keep.formUnion(s.standaloneAlarms.compactMap { $0.soundUri })
        return keep
    }

    // MARK: - ユーティリティ

    static var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    static func hm(_ millis: Int64) -> String {
        let f = DateFormatter()
        f.dateFormat = "M/d HH:mm"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }

    static func hmOnly(_ millis: Int64) -> String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }

    /// NOTIFY 配信: 期限切れ分はその場で通知を出す。
    private func deliverNotifyNow(_ inst: AlarmInstanceDTO) {
        scheduleEventNotification(inst, atMillis: nil)
        store.putScheduled(ScheduledRecord(instance: inst, state: .fired))
        store.audit("NOTIFY", "\(inst.title)")
    }

    /// 予定の「通知のみ」アクションの通知を出す (atMillis=nil なら即時)。
    private func scheduleEventNotification(_ inst: AlarmInstanceDTO, atMillis: Int64?) {
        let content = UNMutableNotificationContent()
        content.title = inst.title
        content.body = inst.minutesBefore <= 0
            ? "開始時刻です"
            : "\(inst.minutesBefore)分前です"
        content.sound = .default
        var trigger: UNNotificationTrigger? = nil
        if let at = atMillis {
            var comps = Calendar.current.dateComponents(
                [.year, .month, .day, .hour, .minute, .second],
                from: Date(timeIntervalSince1970: TimeInterval(at) / 1000))
            // 生成時のTZに固定しないと、TZ変更後に dateComponents の解釈がずれて
            // 通知時刻が狂う (timeZone フィールドがトリガ解釈に使われる)
            comps.timeZone = Calendar.current.timeZone
            trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
        }
        let req = UNNotificationRequest(
            identifier: "notify-\(inst.id)", content: content, trigger: trigger)
        UNUserNotificationCenter.current().add(req) { _ in }
    }

    /// MISSED になったアラームの通知 (通知権限がある時のみ実際に出る)。
    private func notifyMissed(_ inst: AlarmInstanceDTO) {
        let content = UNMutableNotificationContent()
        content.title = "鳴らせなかったアラーム"
        content.body = "\(inst.title) (\(Self.hm(inst.triggerAtMillis)))"
        content.sound = .default
        let req = UNNotificationRequest(identifier: "missed-\(inst.id)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req) { _ in }
    }
}
