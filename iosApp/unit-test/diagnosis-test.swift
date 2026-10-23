import XCTest
import UserNotifications
@testable import iosApp

/// 通知で鳴らす iOS (<26) の偽の予約。予約は id を返すだけで、OS には何も入れない
final class NotificationPathScheduler: AlarmScheduler {
    var usesRealAlarms: Bool { false }
    var authorizationGranted: Bool { true }
    var authorizationUndetermined: Bool { false }
    func requestAuthorization() async {}
    func schedule(_ inst: AlarmInstanceDTO, sound: String?) async throws -> String? { inst.id }
    func cancel(instanceId: String, reservationId: String?) async {}
    func hasReservation(instanceId: String) async -> Bool { true }
    func unreservedPendingIds(_ scheduled: [String: ScheduledRecord]) async -> Set<String>? { nil }
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
    /// submit に渡された依頼。NotificationScheduler が OS に何を予約するかをそのまま見る
    var submitted: [UNNotificationRequest] = []
    var submitError: Error?
    func submit(_ request: UNNotificationRequest) async throws {
        if let submitError { throw submitError }
        submitted.append(request)
    }
}

/// UNNotification / UNNotificationResponse は公開の init が無い。OS が届けた物と同じ型を、
/// NSCoder から復元して作る (delegate の本物のメソッドと、通知センターの読み取りを実行するため)
private final class StubCoder: NSCoder {
    private let values: [String: Any]
    init(_ values: [String: Any]) { self.values = values }
    override var allowsKeyedCoding: Bool { true }
    override var requiresSecureCoding: Bool { false }
    override func containsValue(forKey key: String) -> Bool { values[key] != nil }
    override func decodeObject(forKey key: String) -> Any? { values[key] }
    override func decodeBool(forKey key: String) -> Bool { (values[key] as? Bool) ?? false }
}

private enum OSNotification {
    static func make(id: String, date: Date) -> UNNotification {
        let request = UNNotificationRequest(identifier: id, content: UNMutableNotificationContent(), trigger: nil)
        let coder = StubCoder(["request": request, "date": date])
        return UNNotification(coder: coder)!
    }

    static func response(id: String, date: Date) -> UNNotificationResponse {
        let coder = StubCoder([
            "notification": make(id: id, date: date),
            "actionIdentifier": UNNotificationDefaultActionIdentifier,
        ])
        return UNNotificationResponse(coder: coder)!
    }
}

/// 読み取り (予約中・届いた) だけ本物の SystemNotificationCenter を通し、消す・足す・許可は偽物に流す。
/// 本物の消す・足すを通すと、テストのホストであるアプリ本体の通知予約を触ってしまう
private final class ReadsThroughSystemCenter: NotificationCenterPort {
    let system: SystemNotificationCenter
    let rest: FakeNotificationCenter
    init(system: SystemNotificationCenter, rest: FakeNotificationCenter) {
        self.system = system
        self.rest = rest
    }
    func pendingRequestIds() async -> [String] { await system.pendingRequestIds() }
    func delivered() async -> [(id: String, atMillis: Int64)] { await system.delivered() }
    func isAuthorized() async -> Bool { await rest.isAuthorized() }
    func removePending(_ ids: [String]) { rest.removePending(ids) }
    func add(_ request: UNNotificationRequest) { rest.add(request) }
    func submit(_ request: UNNotificationRequest) async throws { try await rest.submit(request) }
}

private final class FakeNotificationSource: UserNotificationSource {
    var pendingList: [UNNotificationRequest] = []
    var deliveredList: [UNNotification] = []
    func pendingNotificationRequests() async -> [UNNotificationRequest] { pendingList }
    func deliveredNotifications() async -> [UNNotification] { deliveredList }
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

    /// 見逃しは設定・予定画面と同じ ringHistory で数える。単発をオンに戻して予約の行が消えても、診断だけ 0 件にならない
    func testRecentFailuresCountsMissedFromRingHistoryLikeSettings() {
        let hour: Int64 = 60 * 60 * 1000
        let missed = AuditEntry(id: 1, atMillis: now - hour, type: "MISSED", detail: "朝",
                                instanceId: "one-shot", triggerAtMillis: now - 2 * hour, title: "朝")
        let history = [missed]
        // 予約の行は引数に無い (オンに戻して消えた後と同じ) のに、設定画面と同じ 1 件になる
        XCTAssertEqual(DiagnosisPlan.recentFailures([], ringHistory: history, nowMillis: now), 1)
        XCTAssertEqual(DiagnosisPlan.recentFailures([], ringHistory: history, nowMillis: now),
                       RingHistory.recentMissedCount(history, nowMillis: now))
        // 同じ予約の MISSED が監査ログにもあっても二重に数えない
        XCTAssertEqual(DiagnosisPlan.recentFailures([missed], ringHistory: history, nowMillis: now), 1)
        // 鳴るはずだった時刻が24時間より前なら、今見つけても直近に入れない
        let old = AuditEntry(id: 2, atMillis: now, type: "MISSED", detail: "昨日",
                             instanceId: "old", triggerAtMillis: now - 30 * hour, title: "昨日")
        XCTAssertEqual(DiagnosisPlan.recentFailures([], ringHistory: [old], nowMillis: now), 0)
    }

    /// 予約の失敗 (MISSED 以外の失敗の種類) は監査ログから数え、見逃しと足す
    func testRecentFailuresAddsScheduleErrorsFromAudit() {
        let hour: Int64 = 60 * 60 * 1000
        let err = AuditEntry(id: 3, atMillis: now - hour, type: "SCHEDULE_ERR", detail: "x")
        let tooOld = AuditEntry(id: 4, atMillis: now - 30 * hour, type: "RESYNC_FAIL", detail: "y")
        let missed = AuditEntry(id: 5, atMillis: now, type: "MISSED", detail: "朝",
                                instanceId: "a", triggerAtMillis: now - hour, title: "朝")
        XCTAssertEqual(DiagnosisPlan.recentFailures([err, tooOld], ringHistory: [missed], nowMillis: now), 2)
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


/// 本番の配線 (NotificationScheduler / SystemNotificationCenter / NotificationPresenter) を通すテスト。
/// ここが壊れると、iOS 26 未満の予約が全部「予約できていない」に見え、届いた証拠も取れなくなる。
/// 他のテストは偽の予約や偽の通知センターを使うので、この配線の退行を拾えない
@MainActor
final class NotificationWiringTests: XCTestCase {
    private var center: FakeNotificationCenter!
    private var engine: Engine!

    override func setUp() async throws {
        center = FakeNotificationCenter()
        engine = Engine(
            scheduler: NotificationScheduler(port: center),
            store: Store(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("store-\(UUID().uuidString).json")),
            notifications: center)
        engine.store.mutate {
            $0.scheduled = [:]
            $0.standaloneAlarms = []
            $0.audit = []
        }
    }

    private func pendingRow(_ id: String, at: Int64) {
        let aid = engine.store.upsertAlarm(StandaloneAlarmDTO(hour: 7, minute: 0, repeatMode: "ONCE"))
        let inst = AlarmInstanceDTO(
            id: id, triggerAtMillis: at, title: "朝", kind: "STANDALONE", standaloneAlarmId: aid)
        engine.store.putScheduled(ScheduledRecord(instance: inst, state: .pending, alarmKitId: id))
    }

    /// 予約できたら、予約した通知の id を返す。nil を返すと全行が「予約できていない」と診断される
    func testNotificationSchedulerReturnsTheIdItReserved() async throws {
        let scheduler = NotificationScheduler(port: center)
        let inst = AlarmInstanceDTO(
            id: "sa:1:x", triggerAtMillis: Engine.nowMillis + 60_000, title: "朝", kind: "STANDALONE")
        let rid = try await scheduler.schedule(inst, sound: nil)
        XCTAssertEqual(rid, "sa:1:x")
        XCTAssertEqual(center.submitted.map { $0.identifier }, ["sa:1:x"])
        XCTAssertTrue(center.submitted.first?.trigger is UNCalendarNotificationTrigger)
    }

    /// OS が予約を断った時は投げる。投げないと、予約できていない行が「予約できた」になる
    func testNotificationSchedulerThrowsWhenTheOSRefuses() async {
        struct Refused: Error {}
        center.submitError = Refused()
        let inst = AlarmInstanceDTO(
            id: "sa:1:x", triggerAtMillis: Engine.nowMillis + 60_000, title: "朝", kind: "STANDALONE")
        do {
            _ = try await NotificationScheduler(port: center).schedule(inst, sound: nil)
            XCTFail("予約を断られたのに投げなかった")
        } catch {
            // OS の拒否をそのまま投げ直していること (別のエラーに化けると原因が追えない)
            XCTAssertTrue(error is Refused, "投げたエラーが OS の拒否ではない: \(error)")
        }
    }

    func testNotificationSchedulerCancelRemovesThePendingRequest() async {
        await NotificationScheduler(port: center).cancel(instanceId: "sa:1:x", reservationId: "sa:1:x")
        XCTAssertEqual(center.removed, ["sa:1:x"])
    }

    /// 本物の NotificationScheduler で予約した行は、診断で「予約できている」になり、次のアラームに選ばれる。
    /// 予約 id が nil に戻ると「このままでは鳴りません」と出る
    func testReservedThroughTheRealSchedulerIsDiagnosedAsReserved() async throws {
        let ok = await engine.scheduleTimer(durationMillis: 60_000, label: "テスト鳴動")
        XCTAssertTrue(ok)
        let rows = Array(engine.store.state.scheduled.values)
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?.alarmKitId, rows.first?.instance.id)
        XCTAssertEqual(DiagnosisPlan.unreservedCount(rows, nowMillis: Engine.nowMillis), 0)
        XCTAssertEqual(DiagnosisPlan.nextAlarm(rows, nowMillis: Engine.nowMillis)?.id, rows.first?.instance.id)
    }

    /// 通知センターに残った通知を resync が読み、届いた行を鳴動済みにする。
    /// SystemNotificationCenter.delivered() が空を返すと、届いた証拠が全部消える
    func testSystemNotificationCenterReadsDeliveredAndPending() async {
        let source = FakeNotificationSource()
        let date = Date(timeIntervalSince1970: 1_800_000_000.5)
        source.deliveredList = [OSNotification.make(id: "sa:1:x", date: date)]
        source.pendingList = [UNNotificationRequest(identifier: "sa:2:y", content: UNMutableNotificationContent(), trigger: nil)]
        let system = SystemNotificationCenter(source: source)
        let delivered = await system.delivered()
        XCTAssertEqual(delivered.map { $0.id }, ["sa:1:x"])
        XCTAssertEqual(delivered.first?.atMillis, 1_800_000_000_500)
        let pending = await system.pendingRequestIds()
        XCTAssertEqual(pending, ["sa:2:y"])
    }

    func testResyncFiresARowThatTheSystemCenterReportsDelivered() async {
        let source = FakeNotificationSource()
        let at = Engine.nowMillis - 60_000
        pendingRow("sa:9:z", at: at)
        source.deliveredList = [OSNotification.make(id: "sa:9:z", date: Date(timeIntervalSince1970: Double(at + 500) / 1000))]
        let wired = Engine(
            scheduler: NotificationScheduler(port: center),
            store: engine.store,
            notifications: ReadsThroughSystemCenter(system: SystemNotificationCenter(source: source), rest: center))
        await wired.resync(reason: "test")
        XCTAssertEqual(engine.store.state.scheduled["sa:9:z"]?.state, .fired)
    }

    /// 通知が届いた知らせは、engine が付く前なら待たせ、付いた時に記録する。
    /// 待たせずに捨てると、起動直後に届いた分が見逃しになる
    func testPresenterBuffersBeforeEngineAndFlushesWhenAttached() {
        let at = Engine.nowMillis - 60_000
        pendingRow("sa:5:q", at: at)
        let presenter = NotificationPresenter()
        presenter.deliver("sa:5:q", at: at + 500)
        XCTAssertEqual(engine.store.state.scheduled["sa:5:q"]?.state, .pending)
        presenter.engine = engine
        XCTAssertEqual(engine.store.state.scheduled["sa:5:q"]?.state, .fired)
        XCTAssertEqual(DiagnosisPlan.lastFired(engine.store.state.audit)?.atMillis, at + 500)
    }

    func testPresenterRecordsDeliveryDirectlyOnceAttached() {
        let at = Engine.nowMillis - 60_000
        pendingRow("sa:6:r", at: at)
        let presenter = NotificationPresenter()
        presenter.engine = engine
        presenter.deliver("sa:6:r", at: at + 500)
        XCTAssertEqual(engine.store.state.scheduled["sa:6:r"]?.state, .fired)
    }

    /// アプリを開いている間の通知はバナーと音で出し、届いた時刻で記録する (OS が渡す本物の型で呼ぶ)
    func testWillPresentShowsBannerAndSoundAndRecordsDelivery() async {
        let at = Engine.nowMillis - 60_000
        pendingRow("sa:7:s", at: at)
        let presenter = NotificationPresenter()
        presenter.engine = engine
        let note = OSNotification.make(id: "sa:7:s", date: Date(timeIntervalSince1970: Double(at + 500) / 1000))
        let options = await presenter.userNotificationCenter(.current(), willPresent: note)
        XCTAssertTrue(options.contains(.banner))
        XCTAssertTrue(options.contains(.sound))
        XCTAssertTrue(options.contains(.list))
        XCTAssertEqual(engine.store.state.scheduled["sa:7:s"]?.state, .fired)
    }

    /// 通知を開いた時は、押した時刻でなく届いた時刻で残す
    func testDidReceiveRecordsDeliveryAtTheDeliveredTime() async {
        let at = Engine.nowMillis - 60_000
        pendingRow("sa:8:t", at: at)
        let presenter = NotificationPresenter()
        presenter.engine = engine
        let res = OSNotification.response(id: "sa:8:t", date: Date(timeIntervalSince1970: Double(at + 500) / 1000))
        await presenter.userNotificationCenter(.current(), didReceive: res)
        XCTAssertEqual(engine.store.state.scheduled["sa:8:t"]?.state, .fired)
        XCTAssertEqual(DiagnosisPlan.lastFired(engine.store.state.audit)?.atMillis, at + 500)
    }
}
