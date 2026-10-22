import XCTest
import UserNotifications
@testable import iosApp

/// 通知で鳴らす iOS (<26) の偽の予約。予約は id を返すだけで、OS には何も入れない
final class NotificationPathScheduler: AlarmScheduler {
    var usesRealAlarms: Bool { false }
    var authorizationGranted: Bool { true }
    var authorizationUndetermined: Bool { false }
    func requestAuthorization() async {}
    func schedule(_ inst: AlarmInstanceDTO) async throws -> String? { inst.id }
    func cancel(instanceId: String, reservationId: String?) async {}
}

/// OS の通知センターの偽物。テストのホストはアプリ本体なので、本物を触るとアプリの通知予約が消える
final class FakeNotificationCenter: NotificationCenterPort {
    var pending: [String] = []
    /// 通知センターに残っている (届いた) 通知。resync の「届いた証拠」になる
    var deliveredList: [(id: String, atMillis: Int64)] = []
    /// 今の通知許可。false だと resync は証拠の無い行を見逃し判定に回す
    var authorized = true
    var removed: [String] = []
    var added: [String] = []
    func pendingRequestIds() async -> [String] { pending }
    func delivered() async -> [(id: String, atMillis: Int64)] { deliveredList }
    func isAuthorized() async -> Bool { authorized }
    func removePending(_ ids: [String]) {
        removed += ids
        pending.removeAll { ids.contains($0) }
    }
    func add(_ request: UNNotificationRequest) { added.append(request.identifier) }
}

final class DiagnosisPlanTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000

    private func row(_ id: String, at: Int64, state: InstanceState = .pending, rid: String? = "r") -> ScheduledRecord {
        ScheduledRecord(
            instance: AlarmInstanceDTO(id: id, triggerAtMillis: at, title: id, kind: "STANDALONE"),
            state: state, alarmKitId: rid)
    }

    /// 通知センターに残る行だけが届いた扱いで、時刻も通知の時刻になる。予約から消えただけの行は分からない側
    func testOutcomeNeedsDeliveryEvidence() {
        let rows = [
            row("in-center", at: now - 60_000),
            row("gone", at: now - 30_000),
            row("still-pending-in-os", at: now - 10_000),
            row("future", at: now + 60_000),
            row("retry-only", at: now - 5_000, rid: nil),
            row("already-fired", at: now - 90_000, state: .fired),
        ]
        let out = Engine.notificationOutcome(
            rows, pendingRequestIds: ["still-pending-in-os", "future"],
            deliveredAtById: ["in-center": now - 59_000], nowMillis: now)
        XCTAssertEqual(out.delivered.map { $0.id }, ["in-center"])
        XCTAssertEqual(out.delivered.first?.at, now - 59_000)
        XCTAssertEqual(out.unconfirmed, ["gone"])
    }

    /// 同じ id で前に予約した通知 (予約時刻より前に届いた物) は、今の予約が届いた証拠にしない。
    /// 秒の端数は予約で切り捨てるので、予約時刻の秒の頭に届いた物は届いた扱い
    func testOlderDeliveryIsNotEvidenceForTheCurrentReservation() {
        let rows = [row("moved", at: now - 60_000), row("on-time", at: now - 30_500)]
        let out = Engine.notificationOutcome(
            rows, pendingRequestIds: [],
            deliveredAtById: ["moved": now - 3_600_000, "on-time": now - 31_000], nowMillis: now)
        XCTAssertEqual(out.delivered.map { $0.id }, ["on-time"])
        XCTAssertEqual(out.unconfirmed, ["moved"])
    }

    /// 「通知のみ」の予定の記録 (NOTIFY) は最後に鳴ったアラームにしない
    func testLastFiredIgnoresNotificationOnlyEvents() {
        let audit = [
            AuditEntry(id: 1, atMillis: now - 3_600_000, type: "FIRED", detail: "朝のアラーム"),
            AuditEntry(id: 2, atMillis: now - 60_000, type: "NOTIFY", detail: "通知だけの予定"),
            AuditEntry(id: 3, atMillis: now - 30_000, type: DiagnosisPlan.unconfirmedType, detail: "分からない"),
        ]
        XCTAssertEqual(DiagnosisPlan.lastFired(audit)?.id, 1)
        XCTAssertNil(DiagnosisPlan.lastFired([audit[1]]))
        XCTAssertEqual(DiagnosisPlan.unconfirmedCount(audit, nowMillis: now), 1)
    }

    /// 予約に失敗して入れ直し待ちの行は次のアラームにせず、予約できていない件数で出す
    func testRetryOnlyRowIsNotTheNextAlarm() {
        let rows = [
            row("retry-only", at: now + 60_000, rid: nil),
            row("booked", at: now + 120_000),
        ]
        XCTAssertEqual(DiagnosisPlan.nextAlarm(rows, nowMillis: now)?.id, "booked")
        XCTAssertNil(DiagnosisPlan.nextAlarm([rows[0]], nowMillis: now))
        XCTAssertEqual(DiagnosisPlan.unreservedCount(rows, nowMillis: now), 1)

        var input = baseInput(usesRealAlarms: true)
        input.nextAlarm = DiagnosisPlan.nextAlarm(rows, nowMillis: now)
        input.unreservedCount = 1
        let items = DiagnosisPlan.items(input)
        XCTAssertEqual(items.first { $0.id == "unreserved" }?.state, .ng)
        XCTAssertEqual(items.first { $0.id == "next" }?.title.hasPrefix("次のアラーム"), true)
    }

    /// 旧版で予約 id を残さなかった行は、OS に予約か届いた通知がある時だけ id を埋める。スヌーズの予約失敗は埋めない
    func testLegacyRowsGetTheirRequestIdOnlyWhenTheOSHasIt() {
        let rows = [
            row("legacy-pending", at: now + 60_000, rid: nil),
            row("legacy-delivered", at: now - 60_000, rid: nil),
            row("failed-snooze", at: now + 60_000, rid: nil),
            row("booked", at: now + 60_000),
            row("legacy-fired", at: now - 60_000, state: .fired, rid: nil),
        ]
        XCTAssertEqual(
            Engine.legacyRequestIds(rows, knownRequestIds: ["legacy-pending", "legacy-delivered", "booked", "legacy-fired"]),
            ["legacy-delivered", "legacy-pending"])
    }

    /// AlarmKit でスヌーズを数えている行は、スヌーズした時刻 + スヌーズ分を次のアラームにする
    func testNativeSnoozeCountsAsTheNextAlarm() {
        var snoozed = row("sa:1:d", at: now - 120_000, state: .snoozed)
        snoozed.instance.snoozeMinutes = 10
        let later = row("booked", at: now + 3_600_000)
        let audit = [AuditEntry(id: 1, atMillis: now - 60_000, type: "SNOOZE", detail: "sa:1:d +10m (native)")]
        let next = DiagnosisPlan.nextAlarm([snoozed, later], audit: audit, countdownIds: ["sa:1:d"], nowMillis: now)
        XCTAssertEqual(next?.id, "sa:1:d")
        XCTAssertEqual(next?.triggerAtMillis, now - 60_000 + 600_000)
        XCTAssertTrue(next?.title.hasSuffix("（スヌーズ中）") ?? false)
        // 数えていない (止められた) スヌーズは入れない
        XCTAssertEqual(DiagnosisPlan.nextAlarm([snoozed, later], audit: audit, countdownIds: [], nowMillis: now)?.id, "booked")
    }

    /// 分からない件数は通知で鳴らす iOS だけに出す。AlarmKit では起きない
    func testUnconfirmedItemOnlyOnNotificationPath() {
        var input = baseInput(usesRealAlarms: false)
        input.unconfirmedCount = 2
        XCTAssertEqual(DiagnosisPlan.items(input).first { $0.id == "unconfirmed" }?.state, .info)
        input.usesRealAlarms = true
        XCTAssertNil(DiagnosisPlan.items(input).first { $0.id == "unconfirmed" })
        input.usesRealAlarms = false
        input.unconfirmedCount = 0
        XCTAssertNil(DiagnosisPlan.items(input).first { $0.id == "unconfirmed" })
    }

    private func baseInput(usesRealAlarms: Bool) -> DiagnosisInput {
        DiagnosisInput(
            calendar: .granted, alarm: .granted, notify: .granted,
            usesRealAlarms: usesRealAlarms, timeSensitiveEnabled: nil, notificationSoundEnabled: nil,
            backgroundRefreshAvailable: true, lowPowerMode: false,
            nextAlarm: nil, lastFired: nil, auditCount: 0, recentFailures: 0, nowMillis: now)
    }
}

@MainActor
final class EngineNotificationOutcomeTests: XCTestCase {
    private var engine: Engine!
    private var center: FakeNotificationCenter!

    override func setUp() async throws {
        // 端末の保存データと通知の予約を触らないよう、一時ファイルの Store と偽の通知センターを渡す
        center = FakeNotificationCenter()
        engine = Engine(
            scheduler: NotificationPathScheduler(),
            store: Store(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("store-\(UUID().uuidString).json")),
            notifications: center)
        engine.store.mutate {
            $0.scheduled = [:]
            $0.standaloneAlarms = []
            $0.audit = []
        }
    }

    override func tearDown() async throws {
        engine.store.mutate {
            $0.scheduled = [:]
            $0.standaloneAlarms = []
            $0.audit = []
        }
    }

    private func pendingOneShot() -> (alarmId: Int64, instanceId: String) {
        let aid = engine.store.upsertAlarm(StandaloneAlarmDTO(hour: 7, minute: 0, repeatMode: "ONCE"))
        let id = "sa:\(aid):test"
        let inst = AlarmInstanceDTO(
            id: id, triggerAtMillis: Engine.nowMillis - 60_000, title: "朝", kind: "STANDALONE",
            standaloneAlarmId: aid)
        engine.store.putScheduled(ScheduledRecord(instance: inst, state: .pending, alarmKitId: id))
        return (aid, id)
    }

    /// resync のテスト用の行。offsetMs は今からの差 (負は過去)。rid が nil なら旧版が残した予約 id 無しの行
    private func oneShotRow(offsetMs: Int64, rid: Bool) -> (alarmId: Int64, instanceId: String, trigger: Int64) {
        let aid = engine.store.upsertAlarm(StandaloneAlarmDTO(hour: 7, minute: 0, repeatMode: "ONCE"))
        let id = "sa:\(aid):resync"
        let trigger = Engine.nowMillis + offsetMs
        let inst = AlarmInstanceDTO(
            id: id, triggerAtMillis: trigger, title: "朝", kind: "STANDALONE", standaloneAlarmId: aid)
        engine.store.putScheduled(ScheduledRecord(instance: inst, state: .pending, alarmKitId: rid ? id : nil))
        return (aid, id, trigger)
    }

    /// 旧版の行 (予約 id 無し) が通知センターに届いた通知を持つ時、resync が id を埋めたうえで
    /// 届いた時刻の鳴動 (FIRED) にする。埋めないと予約失敗の行に見えて、届いても見逃しになる
    func testResyncFillsLegacyIdAndFiresFromDeliveredNotification() async {
        let row = oneShotRow(offsetMs: -60_000, rid: false)
        let deliveredAt = row.trigger + 800
        center.deliveredList = [(row.instanceId, deliveredAt)]
        await engine.resync(reason: "test")

        let rec = engine.store.state.scheduled[row.instanceId]
        XCTAssertEqual(rec?.state, .fired)
        XCTAssertEqual(rec?.alarmKitId, row.instanceId)
        XCTAssertEqual(DiagnosisPlan.lastFired(engine.store.state.audit)?.atMillis, deliveredAt)
        XCTAssertFalse(engine.store.state.audit.contains { $0.type == "MISSED" })
        XCTAssertEqual(engine.store.state.standaloneAlarms.first { $0.id == row.alarmId }?.enabled, false)
    }

    /// 予約から消えて届いた証拠も無い行は、今の通知許可で分かれる。
    /// 許可が無ければ時刻に出なかった側なので、猶予 (1 時間) を過ぎたら見逃し (MISSED)。UNCONFIRMED にしない
    func testResyncCountsGoneRowAsMissedWhenNotAuthorized() async {
        let row = oneShotRow(offsetMs: -2 * 3_600_000, rid: true)
        center.authorized = false
        await engine.resync(reason: "test")

        XCTAssertEqual(engine.store.state.scheduled[row.instanceId]?.state, .missed)
        XCTAssertTrue(engine.store.state.audit.contains { $0.type == "MISSED" })
        XCTAssertFalse(engine.store.state.audit.contains { $0.type == DiagnosisPlan.unconfirmedType })
    }

    /// 許可があるのに証拠が無い行は、見逃しと言い切れないので UNCONFIRMED (見逃しにも鳴動にも数えない)
    func testResyncMarksGoneRowUnconfirmedWhenAuthorized() async {
        let row = oneShotRow(offsetMs: -2 * 3_600_000, rid: true)
        center.authorized = true
        await engine.resync(reason: "test")

        XCTAssertEqual(engine.store.state.scheduled[row.instanceId]?.state, .fired)
        XCTAssertTrue(engine.store.state.audit.contains { $0.type == DiagnosisPlan.unconfirmedType })
        XCTAssertFalse(engine.store.state.audit.contains { $0.type == "MISSED" })
        XCTAssertFalse(engine.store.state.audit.contains { $0.type == "FIRED" })
    }

    /// 許可が無くても猶予内の行は、見逃しにも UNCONFIRMED にもしない (見逃し判定は猶予の後)
    func testResyncDoesNotJudgeGoneRowWithinGraceWhenNotAuthorized() async {
        _ = oneShotRow(offsetMs: -60_000, rid: true)
        center.authorized = false
        await engine.resync(reason: "test")

        XCTAssertFalse(engine.store.state.audit.contains { $0.type == "MISSED" || $0.type == DiagnosisPlan.unconfirmedType })
    }

    /// 届いた証拠の無い行は鳴動の記録 (FIRED) にも見逃しにもせず、「1回のみ」は消費する
    func testUnconfirmedIsNeitherFiredNorMissed() {
        let (aid, id) = pendingOneShot()
        engine.markNotificationUnconfirmed(id)
        XCTAssertEqual(engine.store.state.scheduled[id]?.state, .fired)
        XCTAssertFalse(engine.store.state.audit.contains { $0.type == "FIRED" })
        XCTAssertFalse(engine.store.state.audit.contains { $0.type == "MISSED" })
        XCTAssertTrue(engine.store.state.audit.contains { $0.type == DiagnosisPlan.unconfirmedType })
        XCTAssertEqual(engine.store.state.standaloneAlarms.first { $0.id == aid }?.enabled, false)
        XCTAssertNil(DiagnosisPlan.lastFired(engine.store.state.audit))
    }

    /// 届いた行は届いた時刻で FIRED を残す
    func testDeliveredKeepsDeliveryTime() {
        let (_, id) = pendingOneShot()
        let at = Engine.nowMillis - 30_000
        engine.markNotificationDelivered(id, deliveredAtMillis: at)
        XCTAssertEqual(DiagnosisPlan.lastFired(engine.store.state.audit)?.atMillis, at)
    }

    /// 同じ日に時刻を変えた「1回のみ」に、変える前の通知を開いた知らせが来ても鳴動済みにしない。
    /// 今の予約の時刻に届いた知らせで初めて鳴動済みになる
    func testOlderDeliveryDoesNotFireTheRescheduledOneShot() {
        let (aid, id) = pendingOneShot()
        let trigger = engine.store.state.scheduled[id]!.instance.triggerAtMillis
        engine.markNotificationDelivered(id, deliveredAtMillis: trigger - 3_600_000)
        XCTAssertEqual(engine.store.state.scheduled[id]?.state, .pending)
        XCTAssertEqual(engine.store.state.standaloneAlarms.first { $0.id == aid }?.enabled, true)
        XCTAssertFalse(engine.store.state.audit.contains { $0.type == "FIRED" })

        engine.markNotificationDelivered(id, deliveredAtMillis: trigger + 500)
        XCTAssertEqual(engine.store.state.scheduled[id]?.state, .fired)
        XCTAssertEqual(DiagnosisPlan.lastFired(engine.store.state.audit)?.atMillis, trigger + 500)
    }

    /// resync の通知の照合は差し替えた通知センターに向く。本物に向くとアプリの通知予約を消す
    func testResyncReconcilesNotificationsThroughTheInjectedCenter() async {
        center.pending = ["notify-ev:gone:0"]
        await engine.resync(reason: "test")
        XCTAssertEqual(center.removed, ["notify-ev:gone:0"])
    }

    /// 届いた「1回のみ」はその場で OFF になり、毎週のアラームは ON のまま。
    /// 終端行が 7 日で掃除された後の resync でも、OFF なので次の日付に展開されて鳴り直さない
    func testDeliveredOneShotStaysOffAfterPruneAndResync() async {
        let (onceId, onceRow) = pendingOneShot()
        let weeklyId = engine.store.upsertAlarm(
            StandaloneAlarmDTO(hour: 8, minute: 0, daysOfWeek: ["MONDAY"], repeatMode: "WEEKLY"))
        let weeklyRow = "sa:\(weeklyId):test"
        engine.store.putScheduled(ScheduledRecord(
            instance: AlarmInstanceDTO(
                id: weeklyRow, triggerAtMillis: Engine.nowMillis - 60_000, title: "毎週", kind: "STANDALONE",
                standaloneAlarmId: weeklyId),
            state: .pending, alarmKitId: weeklyRow))

        engine.markNotificationDelivered(onceRow)
        engine.markNotificationDelivered(weeklyRow)
        XCTAssertEqual(engine.store.state.scheduled[onceRow]?.state, .fired)
        XCTAssertEqual(engine.store.state.standaloneAlarms.first { $0.id == onceId }?.enabled, false)
        XCTAssertEqual(engine.store.state.standaloneAlarms.first { $0.id == weeklyId }?.enabled, true)

        engine.store.pruneOldInstances(nowMillis: Engine.nowMillis + 8 * 86_400_000)
        XCTAssertNil(engine.store.state.scheduled[onceRow])
        await engine.resync(reason: "test")

        let pendingAlarmIds = engine.store.state.scheduled.values
            .filter { $0.state == .pending }
            .compactMap { $0.instance.standaloneAlarmId }
        // 毎週の方が展開されていれば、resync は展開まで走っている (空振りで通る試験にしない)
        XCTAssertTrue(pendingAlarmIds.contains(weeklyId))
        XCTAssertFalse(pendingAlarmIds.contains(onceId))
        XCTAssertEqual(engine.store.state.standaloneAlarms.first { $0.id == onceId }?.enabled, false)
    }
}

@MainActor
final class StoreAuditOrderTests: XCTestCase {
    /// 後から分かった出来事 (閉じている間に届いた通知) は、末尾でなく起きた時刻の位置に入る
    func testAuditWithEarlierTimeLandsInTimeOrder() {
        let store = Store(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("store-\(UUID().uuidString).json"))
        store.mutate { $0.audit = [] }
        store.audit("A", "first", atMillis: 1_000)
        store.audit("C", "third", atMillis: 3_000)
        let idBefore = store.state.nextAuditId
        store.audit("B", "second", atMillis: 2_000)
        store.audit("Z", "earliest", atMillis: 500)
        store.audit("C2", "same-time", atMillis: 3_000)

        XCTAssertEqual(store.state.audit.map { $0.type }, ["Z", "A", "B", "C", "C2"])
        XCTAssertEqual(store.state.audit.map { $0.atMillis }, [500, 1_000, 2_000, 3_000, 3_000])
        // 差し込んでも採番は増える (診断画面の作り直しの合図に使う)
        XCTAssertEqual(store.state.nextAuditId, idBefore + 3)
    }
}
