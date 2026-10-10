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

    let store = Store()
    let reader = EventKitReader()
    private let scheduler: AlarmScheduler

    /// 展開ホライズン (Android と同じ 14 日)
    private let horizonDays = 14

    /// 鳴動中のインスタンス id (RingingView 用)
    @Published var alertingIds: Set<String> = []

    init() {
        if #available(iOS 26.0, *) {
            scheduler = AlarmKitScheduler()
        } else {
            scheduler = NotificationScheduler()
        }
        observeAlarmUpdates()
    }

    var usesRealAlarms: Bool { scheduler.usesRealAlarms }
    var schedulerAuthorized: Bool { scheduler.authorizationGranted }

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
                    if let aid = rec.instance.standaloneAlarmId { disableConsumedOneShots([aid]) }
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

        let expandReq = ExpandRequest(
            events: events,
            standalone: s.standaloneAlarms.filter {
                $0.effectiveRepeatMode != "ONCE" || !consumedAlarmIds.contains($0.id)
            },
            calendarRules: s.calendarRules,
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
            (s.calendarRules[$0.calendarId]?.enabled ?? true)
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

        // AlarmKit 側で予約だけが消えた PENDING 行を拾い直す (再起動や OS 内部の
        // 破棄でストア上は PENDING だが実体は無い → 二度と鳴らない穴を塞ぐ。
        // Android は毎回全件 AlarmManager へ再主張する方式で同じ問題を潰している)。
        if #available(iOS 26.0, *), let ak = scheduler as? AlarmKitScheduler, ak.authorizationGranted {
            let liveIds = Set(ak.allAlarms.map { $0.id })
            for (id, rec) in store.state.scheduled where rec.state == .pending {
                if !liveIds.contains(AlarmKitScheduler.uuid(from: id)) {
                    store.removeScheduled(id)
                    store.audit("RECOVER", "\(id): AlarmKit予約喪失→再予約")
                }
            }
        }

        let plan = SharedDomain.plan(PlanRequest(
            desired: desired,
            scheduled: store.pendingScheduled(),
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
                let rid = try await scheduler.schedule(shifted)
                store.putScheduled(ScheduledRecord(instance: inst, state: .pending, alarmKitId: rid))
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
                let rid = try await scheduler.schedule(inst)
                store.putScheduled(ScheduledRecord(instance: inst, state: .pending, alarmKitId: rid))
                scheduledCount += 1
            } catch {
                store.audit("SCHEDULE_ERR", "\(inst.id): \(error.localizedDescription)")
            }
        }

        store.pruneOldInstances(nowMillis: now)
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
    func snooze(_ inst: AlarmInstanceDTO) {
        if #available(iOS 26.0, *), let ak = scheduler as? AlarmKitScheduler,
           (try? ak.countdown(uuid: AlarmKitScheduler.uuid(from: inst.id))) != nil {
            alertingIds.remove(inst.id)
            store.markState(inst.id, .snoozed)
            store.audit("SNOOZE", "\(inst.id) +\(inst.snoozeMinutes)m (native)")
            return
        }
        // countdown が投げた = 元アラームが alerting でない/居ない。
        // まだ鳴り続けているのに UI だけ閉じると二重鳴動の元を残すので、
        // 子予約の前に元を静かにしておく (止められないなら子も足さない)。
        if #available(iOS 26.0, *), let ak = scheduler as? AlarmKitScheduler,
           !ak.silence(uuid: AlarmKitScheduler.uuid(from: inst.id)) {
            store.audit("ERROR", "スヌーズ失敗(元アラーム鳴動継続): \(inst.id)")
            return
        }
        alertingIds.remove(inst.id)
        let next = AlarmInstanceDTO(
            id: "\(inst.id):snz\(inst.snoozeSeq + 1)",
            triggerAtMillis: Self.nowMillis + Int64(inst.snoozeMinutes) * 60_000,
            title: inst.title,
            kind: inst.kind,
            eventId: inst.eventId,
            standaloneAlarmId: inst.standaloneAlarmId,
            snoozeMinutes: inst.snoozeMinutes,
            soundUri: inst.soundUri,
            minutesBefore: inst.minutesBefore,
            eventStartMillis: inst.eventStartMillis,
            snoozeSeq: inst.snoozeSeq + 1
        )
        store.markState(inst.id, .snoozed)
        Task {
            // 予約失敗 (権限剥奪等) でも記録は .pending で残す — 次回 resync の
            // 温存経路で再予約され権限が戻れば自律回復する。ただし失敗は監査に残す
            // (try? で握り潰すとゾンビ予約がサイレントのまま二度と鳴らない)。
            var rid: String? = nil
            do {
                rid = try await scheduler.schedule(next)
                store.audit("SNOOZE", "\(inst.id) +\(inst.snoozeMinutes)m → \(next.id)")
            } catch {
                store.audit("SCHEDULE_ERR", "snooze \(next.id): \(error.localizedDescription)")
            }
            store.putScheduled(ScheduledRecord(instance: next, state: .pending, alarmKitId: rid))
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
            let rid = try await scheduler.schedule(inst)
            store.putScheduled(ScheduledRecord(instance: inst, state: .pending, alarmKitId: rid))
            store.audit("TIMER_SET", "\(durationMillis / 1000)s")
        } catch {
            store.audit("SCHEDULE_ERR", "timer: \(error.localizedDescription)")
        }
    }

    func cancelInstance(_ id: String) async {
        await scheduler.cancel(instanceId: id, reservationId: store.state.scheduled[id]?.alarmKitId)
        UNUserNotificationCenter.current()
            .removePendingNotificationRequests(withIdentifiers: ["notify-\(id)"])
        store.removeScheduled(id)
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
