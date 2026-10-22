import XCTest
@testable import iosApp

/// AlarmKit の代わり。予約は覚えるだけで、一覧には覚えている予約だけを返す
private final class OffAppStopFakeScheduler: AlarmScheduler {
    var usesRealAlarms: Bool { true }
    var authorizationGranted: Bool { true }
    var authorizationUndetermined: Bool { false }
    func requestAuthorization() async {}

    /// OS に入っている予約 (instanceId)
    var booked: Set<String> = []
    /// schedule が呼ばれた順の instanceId
    var scheduledIds: [String] = []

    func schedule(_ inst: AlarmInstanceDTO, sound: String?) async throws -> String? {
        booked.insert(inst.id)
        scheduledIds.append(inst.id)
        return ReservationUUID.from(inst.id).uuidString
    }

    func cancel(instanceId: String, reservationId: String?) async {
        booked.remove(instanceId)
    }

    var liveReservationIdsIfReadable: Set<UUID>? { Set(booked.map(ReservationUUID.from)) }

    func hasReservation(instanceId: String) async -> Bool { booked.contains(instanceId) }

    func unreservedPendingIds(_ scheduled: [String: ScheduledRecord]) async -> Set<String>? {
        ReservationGap.unreserved(scheduled) { booked.contains($0) }
    }
}

/// #61: アプリが止まっている間に鳴って、停止ボタンで止めたアラームを、次の resync で鳴らし直さない。
/// 停止ボタンの intent を実際に走らせ、その書き残しを Engine.resync が読むところまで通す
@MainActor
final class EngineOffAppStopTests: XCTestCase {
    private var engine: Engine!
    private var fake: OffAppStopFakeScheduler!
    private var storeURL: URL!

    override func setUp() async throws {
        fake = OffAppStopFakeScheduler()
        // 保存を失敗させる試験のため、書き込み権限を外せる専用のディレクトリに置く
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("offapp-store-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        storeURL = dir.appendingPathComponent("store.json")
        // 端末の保存データを上書きしないよう、一時ファイルの Store を渡す
        engine = Engine(scheduler: fake, store: Store(fileURL: storeURL))
        engine.store.mutate {
            $0.scheduled = [:]
            $0.standaloneAlarms = []
        }
        UserDefaults.standard.removeObject(forKey: OffAppStopLog.key)
    }

    override func tearDown() async throws {
        UserDefaults.standard.removeObject(forKey: OffAppStopLog.key)
        let dir = storeURL.deletingLastPathComponent()
        try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: dir.path)
        try? FileManager.default.removeItem(at: dir)
    }

    /// AlarmKit に予約済みで、もう鳴り終わって一覧から消えた行を置く (アプリが見ていない間に鳴った後の状態)。
    /// 題名に id を入れ、どの監査行もこの行を指せるようにする
    private func placeRungRow(minutesAgo: Int) -> String {
        let id = "sa:offapp-test-\(UUID().uuidString.lowercased())"
        let inst = AlarmInstanceDTO(id: id, triggerAtMillis: Engine.nowMillis - Int64(minutesAgo) * 60_000,
                                    title: id, kind: "STANDALONE")
        engine.store.putScheduled(ScheduledRecord(instance: inst, state: .pending,
                                                  alarmKitId: ReservationUUID.from(id).uuidString))
        return id
    }

    private func audited(_ type: String, _ id: String) -> Bool {
        engine.store.state.audit.contains { $0.type == type && $0.detail.contains(id) }
    }

    private func pressStop(_ id: String) async throws {
        guard #available(iOS 26.0, *) else { throw XCTSkip("AlarmKit の停止 intent は iOS 26 から") }
        _ = try await AlarmStopIntent(instanceId: id).perform()
        XCTAssertNotNil(OffAppStopLog().entries[id], "停止 intent が書き残していない")
    }

    private func assertDismissedOnly(_ id: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(engine.store.state.scheduled[id]?.state, .dismissed, "止めた行が停止済みになっていない",
                       file: file, line: line)
        XCTAssertTrue(audited("DISMISS_OFFAPP", id), "DISMISS_OFFAPP が無い", file: file, line: line)
        XCTAssertFalse(audited("RECOVER", id), "止めたのに拾い直した (鳴り直す)", file: file, line: line)
        XCTAssertFalse(audited("MISSED", id), "止めたのに見逃し通知を出した", file: file, line: line)
        XCTAssertFalse(fake.scheduledIds.contains(id), "止めたのに OS へ予約し直した", file: file, line: line)
        XCTAssertNil(OffAppStopLog().entries[id], "使った書き残しが残っている", file: file, line: line)
    }

    /// 1時間を過ぎてから開いても、見逃し判定より先に停止済みにする
    func testStopWhileAwayIsDismissedAfterGrace() async throws {
        let id = placeRungRow(minutesAgo: 120)
        try await pressStop(id)

        await engine.resync(reason: "test")

        assertDismissedOnly(id)
    }

    /// 1時間以内に開いても、予約喪失として拾い直さない
    func testStopWhileAwayIsDismissedWithinGrace() async throws {
        let id = placeRungRow(minutesAgo: 10)
        try await pressStop(id)

        await engine.resync(reason: "test")

        assertDismissedOnly(id)
    }

    /// 書き残しが無ければ止めた扱いにしない。1時間を過ぎていれば従来どおり見逃し
    func testWithoutStopRecordAfterGraceIsMissed() async throws {
        let id = placeRungRow(minutesAgo: 120)

        await engine.resync(reason: "test")

        XCTAssertEqual(engine.store.state.scheduled[id]?.state, .missed)
        XCTAssertTrue(audited("MISSED", id))
        XCTAssertFalse(audited("DISMISS_OFFAPP", id))
    }

    /// 書き残しが無ければ止めた扱いにしない。1時間以内なら従来どおり予約喪失として拾い直す
    func testWithoutStopRecordWithinGraceIsRecovered() async throws {
        let id = placeRungRow(minutesAgo: 10)

        await engine.resync(reason: "test")

        XCTAssertTrue(audited("RECOVER", id))
        XCTAssertFalse(audited("DISMISS_OFFAPP", id))
        // 拾い直した行は実際に予約し直されて鳴る。監査の文言だけでなく、予約が入ったことを見る
        XCTAssertTrue(fake.scheduledIds.contains(id), "拾い直したのに予約し直していない (鳴らない)")
        XCTAssertEqual(engine.store.state.scheduled[id]?.state, .pending)
    }

    /// 停止済みの保存がディスクに載らないまま落ちても、止めた証拠 (書き残し) は残る。
    /// 消すと、次の起動で PENDING に戻った行が証拠を失って拾い直され、止めたのに鳴り直す (#61)
    func testStopRecordSurvivesWhenDismissedStateIsNotPersisted() async throws {
        let id = placeRungRow(minutesAgo: 10)
        try await pressStop(id)
        // PENDING の行をディスクへ流しきってから、書き込みだけを失敗させる
        XCTAssertEqual(engine.store.savedState()?.scheduled[id]?.state, .pending)
        let dir = storeURL.deletingLastPathComponent()
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: dir.path)

        await engine.resync(reason: "test")

        XCTAssertEqual(engine.store.state.scheduled[id]?.state, .dismissed, "メモリ上は停止済みになる")
        XCTAssertEqual(engine.store.savedState()?.scheduled[id]?.state, .pending, "試験の前提: 保存は失敗している")
        XCTAssertNotNil(OffAppStopLog().entries[id], "保存が済んでいないのに書き残しを消した")

        // アプリが落ちて起動し直した状態: ディスクの PENDING 行を読み、書き残しだけが頼り
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: dir.path)
        let relaunched = Engine(scheduler: fake, store: Store(fileURL: storeURL))
        XCTAssertEqual(relaunched.store.state.scheduled[id]?.state, .pending)

        await relaunched.resync(reason: "test")

        let audit = relaunched.store.state.audit
        XCTAssertEqual(relaunched.store.state.scheduled[id]?.state, .dismissed, "起動し直した後に止めた行が停止済みにならない")
        XCTAssertTrue(audit.contains { $0.type == "DISMISS_OFFAPP" && $0.detail.contains(id) })
        XCTAssertFalse(audit.contains { $0.type == "RECOVER" && $0.detail.contains(id) }, "止めたのに拾い直した (鳴り直す)")
        XCTAssertFalse(fake.scheduledIds.contains(id), "止めたのに OS へ予約し直した")
        XCTAssertNil(OffAppStopLog().entries[id], "保存が済んだ後の書き残しが残っている")
    }

    /// 予約に渡す設定に停止ボタンの intent が付いている。付け忘れると書き残しが残らず #61 が戻る
    func testReservationCarriesStopIntent() throws {
        guard #available(iOS 26.0, *) else { throw XCTSkip("AlarmKit は iOS 26 から") }
        let inst = AlarmInstanceDTO(id: "sa:7:2026-10-22", triggerAtMillis: Engine.nowMillis + 3_600_000,
                                    title: "t", kind: "STANDALONE")
        let children = Mirror(reflecting: AlarmKitScheduler.configuration(for: inst)).children
        let value = children.first { $0.label == "stopIntent" }?.value
        let labels = children.compactMap { $0.label }.joined(separator: ",")
        let stop = try XCTUnwrap(value.flatMap { $0 as? AlarmStopIntent },
                                 "停止ボタンに AlarmStopIntent が付いていない (設定の項目: \(labels))")
        XCTAssertEqual(stop.instanceId, inst.id)
    }
}
