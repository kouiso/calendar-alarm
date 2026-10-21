import XCTest
@testable import iosApp

/// OS の予約の偽物。AlarmKit と同じく、予約の前に同じ id の予約を取り消す
final class FakeScheduler: AlarmScheduler {
    var usesRealAlarms: Bool { false }
    var authorizationGranted: Bool { true }
    var authorizationUndetermined: Bool { false }
    func requestAuthorization() async {}

    /// id → OS に入っている予約と、その予約に渡した音
    var booked: [String: (inst: AlarmInstanceDTO, sound: String?)] = [:]
    /// この回数だけ予約を失敗させる
    var failures = 0
    /// false = 取り消す前に失敗する (権限切れなど)
    var cancelBeforeFailing = true
    /// 予約の途中で一度だけ走らせる処理 (await の間の割り込みを再現する)
    var duringSchedule: (() async -> Void)?

    struct Failure: Error {}

    func schedule(_ inst: AlarmInstanceDTO, sound: String?) async throws -> String? {
        if failures > 0 && !cancelBeforeFailing {
            failures -= 1
            throw Failure()
        }
        booked.removeValue(forKey: inst.id)
        if let hook = duringSchedule {
            duringSchedule = nil
            await hook()
        }
        if failures > 0 {
            failures -= 1
            throw Failure()
        }
        booked[inst.id] = (inst, sound)
        return "fake-\(inst.id)"
    }

    func cancel(instanceId: String, reservationId: String?) async {
        booked.removeValue(forKey: instanceId)
    }

    func hasReservation(instanceId: String) async -> Bool {
        booked[instanceId] != nil
    }
}

@MainActor
final class EngineSoundChangeTests: XCTestCase {
    private var engine: Engine!
    private var fake: FakeScheduler!
    private var sound: String!

    override func setUp() async throws {
        fake = FakeScheduler()
        // 端末の保存データを上書きしないよう、一時ファイルの Store を渡す
        engine = Engine(scheduler: fake, store: Store(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("store-\(UUID().uuidString).json")))
        engine.store.mutate {
            $0.scheduled = [:]
            $0.standaloneAlarms = []
            $0.alarmSoundFile = nil
        }
        AlarmSoundLibrary.currentDefault = nil
        try FileManager.default.createDirectory(at: AlarmSoundLibrary.soundsDirectory,
                                                withIntermediateDirectories: true)
        sound = "\(AlarmSoundLibrary.prefix)test-\(UUID().uuidString.lowercased()).caf"
    }

    /// 取り込んだ直後の音のファイルを置く。resync の最後の片付けはどの記録も指さない音を消すので、
    /// アプリと同じく「取り込み → すぐ既定に設定」の順にするため、予約を作った後に置く
    private func importTestSound() {
        FileManager.default.createFile(
            atPath: AlarmSoundLibrary.soundsDirectory.appendingPathComponent(sound).path, contents: Data([0]))
    }

    override func tearDown() async throws {
        engine.store.mutate {
            $0.scheduled = [:]
            $0.standaloneAlarms = []
            $0.alarmSoundFile = nil
        }
        AlarmSoundLibrary.currentDefault = nil
        AlarmSoundLibrary.remove(sound)
    }

    /// 今から [hours] 時間後の単発アラームを作り、resync で既定音のまま予約させる
    private func bookAlarm(hoursAhead hours: Int) async throws -> (alarmId: Int64, instanceId: String) {
        let at = Date().addingTimeInterval(TimeInterval(hours) * 3600)
        let c = Calendar.current.dateComponents([.hour, .minute], from: at)
        let aid = engine.store.upsertAlarm(StandaloneAlarmDTO(hour: c.hour!, minute: c.minute!, repeatMode: "ONCE"))
        await engine.resync(reason: "test setup")
        let rec = try XCTUnwrap(engine.store.state.scheduled.values.first { $0.instance.standaloneAlarmId == aid })
        XCTAssertNotNil(fake.booked[rec.instance.id])
        XCTAssertNil(rec.soundFile)
        importTestSound()
        return (aid, rec.instance.id)
    }

    /// 入れ直しが取り消しの後に失敗しても、前の音で予約し直して予約を失わない
    func testFailedRebookRestoresPreviousSound() async throws {
        let (_, id) = try await bookAlarm(hoursAhead: 3)
        engine.store.setAlarmSound(file: sound, label: "test")
        fake.failures = 1

        await engine.rescheduleForSoundChange()

        XCTAssertNotNil(fake.booked[id], "OS に予約が残っていない")
        XCTAssertNil(fake.booked[id]?.sound, "前の音 (既定音) で予約し直していない")
        XCTAssertEqual(engine.store.state.scheduled[id]?.state, .pending)
        XCTAssertNil(engine.store.state.scheduled[id]?.soundFile)
        XCTAssertTrue(engine.store.state.audit.contains { $0.type == "SOUND_RESTORE" })
    }

    /// 前の音での予約し直しも失敗したら、記録を外して resync で取り直す
    func testLostReservationIsBookedAgainByResync() async throws {
        let (_, id) = try await bookAlarm(hoursAhead: 3)
        engine.store.setAlarmSound(file: sound, label: "test")
        fake.failures = 2

        await engine.rescheduleForSoundChange()

        XCTAssertTrue(engine.store.state.audit.contains { $0.detail.hasPrefix("sound restore \(id)") })
        XCTAssertEqual(fake.booked[id]?.sound, sound, "resync が新しい音で取り直していない")
        XCTAssertEqual(engine.store.state.scheduled[id]?.soundFile, sound)
        XCTAssertEqual(engine.store.state.scheduled[id]?.state, .pending)
    }

    /// OS に予約がもう無い時は、一覧に予約ありと出さない (resync でも取れない時)
    func testLostReservationIsNotListedWhenResyncAlsoFails() async throws {
        let (_, id) = try await bookAlarm(hoursAhead: 3)
        engine.store.setAlarmSound(file: sound, label: "test")
        fake.failures = 3

        await engine.rescheduleForSoundChange()

        XCTAssertNil(fake.booked[id])
        XCTAssertNil(engine.store.state.scheduled[id], "OS に無い予約が一覧に残っている")
    }

    /// 取り消す前に失敗した時は前の予約が OS に残るので、記録もそのまま残す
    func testFailureBeforeCancelKeepsRecord() async throws {
        let (_, id) = try await bookAlarm(hoursAhead: 3)
        engine.store.setAlarmSound(file: sound, label: "test")
        fake.cancelBeforeFailing = false
        fake.failures = 1

        await engine.rescheduleForSoundChange()

        XCTAssertNotNil(fake.booked[id])
        XCTAssertNil(fake.booked[id]?.sound)
        XCTAssertEqual(engine.store.state.scheduled[id]?.state, .pending)
        XCTAssertNil(engine.store.state.scheduled[id]?.soundFile)
    }

    /// 入れ直しの途中で時刻を変えて resync が走っても、古い時刻で予約し直さない
    func testTimeEditDuringRebookKeepsNewTime() async throws {
        let (aid, id) = try await bookAlarm(hoursAhead: 3)
        let oldTrigger = try XCTUnwrap(engine.store.state.scheduled[id]?.instance.triggerAtMillis)
        engine.store.setAlarmSound(file: sound, label: "test")
        let later = Date().addingTimeInterval(4 * 3600)
        let c = Calendar.current.dateComponents([.hour, .minute], from: later)
        var resyncTask: Task<Void, Never>?
        fake.duringSchedule = { [engine] in
            guard let engine else { return }
            var a = engine.store.state.standaloneAlarms.first { $0.id == aid }!
            a.hour = c.hour!
            a.minute = c.minute!
            _ = engine.store.upsertAlarm(a)
            // 画面の保存と同じく、待たずに resync を投げる
            resyncTask = Task { await engine.resync(reason: "alarm save") }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }

        await engine.rescheduleForSoundChange()
        await resyncTask?.value

        let mine = fake.booked.values.filter { $0.inst.standaloneAlarmId == aid }
        XCTAssertEqual(mine.count, 1, "同じアラームの予約が OS に複数ある")
        XCTAssertNotEqual(mine.first?.inst.triggerAtMillis, oldTrigger, "古い時刻で鳴る")
        XCTAssertEqual(mine.first?.sound, sound)
        let rec = try XCTUnwrap(engine.store.state.scheduled.values.first {
            $0.instance.standaloneAlarmId == aid && $0.state == .pending })
        XCTAssertEqual(rec.instance.triggerAtMillis, mine.first?.inst.triggerAtMillis)
    }

    /// 入れ直しの途中でタイマーを取り消しても、入れ直しが書き戻して鳴らすことはない
    func testCancelDuringRebookStaysCancelled() async throws {
        await engine.scheduleTimer(durationMillis: 3_600_000, label: "test")
        let id = try XCTUnwrap(engine.store.state.scheduled.values.first { $0.instance.kind == "TIMER" }?.instance.id)
        importTestSound()
        engine.store.setAlarmSound(file: sound, label: "test")
        var cancelTask: Task<Void, Never>?
        fake.duringSchedule = { [engine] in
            guard let engine else { return }
            cancelTask = Task { await engine.cancelInstance(id) }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }

        await engine.rescheduleForSoundChange()
        await cancelTask?.value

        XCTAssertNil(fake.booked[id], "取り消したタイマーが OS に残っている")
        XCTAssertNil(engine.store.state.scheduled[id], "取り消したタイマーの記録が戻っている")
    }

    /// 新しい音でも前の音でも予約できなかったタイマーは、展開で作り直せないので記録を残して拾い直させる
    func testLostTimerIsKeptForRecovery() async throws {
        await engine.scheduleTimer(durationMillis: 3_600_000, label: "test")
        let id = try XCTUnwrap(engine.store.state.scheduled.values.first { $0.instance.kind == "TIMER" }?.instance.id)
        importTestSound()
        engine.store.setAlarmSound(file: sound, label: "test")
        fake.failures = 3

        await engine.rescheduleForSoundChange()

        let rec = try XCTUnwrap(engine.store.state.scheduled[id], "タイマーの記録が消えた")
        XCTAssertEqual(rec.state, .pending)
        XCTAssertNil(rec.alarmKitId, "OS に予約が無いのに予約ありになっている")
    }

    /// スヌーズ子も展開で作り直せない。新しい音でも前の音でも予約できなかった時に記録を消すと、二度と鳴らない
    func testLostSnoozeChildIsKeptForRecovery() async throws {
        let (aid, parentId) = try await bookAlarm(hoursAhead: 3)
        let parent = try XCTUnwrap(engine.store.state.scheduled[parentId]?.instance)
        var child = parent
        child.id = "\(parentId):snz1"
        child.snoozeSeq = 1
        child.triggerAtMillis = Engine.nowMillis + 10 * 60_000
        engine.store.markState(parentId, .snoozed)
        engine.store.putScheduled(ScheduledRecord(instance: child, state: .pending, alarmKitId: "fake-\(child.id)"))
        fake.booked[child.id] = (child, nil)
        engine.store.setAlarmSound(file: sound, label: "test")
        fake.failures = 3

        await engine.rescheduleForSoundChange()

        let rec = try XCTUnwrap(engine.store.state.scheduled[child.id], "スヌーズ子の記録が消えた")
        XCTAssertEqual(rec.state, .pending)
        XCTAssertNil(rec.alarmKitId, "OS に予約が無いのに予約ありになっている")
        XCTAssertEqual(rec.instance.standaloneAlarmId, aid)
    }

    /// カレンダーを読めない間は、予定の行も展開で作り直せない。予約できなかった時に記録を消すと、許可が戻るまで鳴らない
    func testLostEventIsKeptWhileCalendarUnreadable() async throws {
        try XCTSkipIf(engine.reader.authorizationGranted, "カレンダーの許可がある端末では再現できない")
        let (_, alarmInstanceId) = try await bookAlarm(hoursAhead: 3)
        var event = try XCTUnwrap(engine.store.state.scheduled[alarmInstanceId]?.instance)
        event.id = "ev:test-\(UUID().uuidString.lowercased()):b0"
        event.kind = "EVENT"
        event.standaloneAlarmId = nil
        engine.store.putScheduled(ScheduledRecord(instance: event, state: .pending, alarmKitId: "fake-\(event.id)"))
        fake.booked[event.id] = (event, nil)
        engine.store.setAlarmSound(file: sound, label: "test")
        fake.failures = 6

        await engine.rescheduleForSoundChange()

        let rec = try XCTUnwrap(engine.store.state.scheduled[event.id], "予定の記録が消えた")
        XCTAssertEqual(rec.state, .pending)
        XCTAssertNil(rec.alarmKitId, "OS に予約が無いのに予約ありになっている")
    }

    /// 展開で作り直せるかの判定。予定はカレンダーを読める時だけ作り直せる
    func testRegeneratedByExpandDependsOnCalendarForEvents() {
        var inst = AlarmInstanceDTO(id: "ev:x:b0", triggerAtMillis: 0, title: "t", kind: "EVENT")
        XCTAssertTrue(Engine.isRegeneratedByExpand(inst, calendarReadable: true))
        XCTAssertFalse(Engine.isRegeneratedByExpand(inst, calendarReadable: false))
        inst.kind = "STANDALONE"
        XCTAssertTrue(Engine.isRegeneratedByExpand(inst, calendarReadable: false))
        inst.snoozeSeq = 1
        XCTAssertFalse(Engine.isRegeneratedByExpand(inst, calendarReadable: true))
        inst.snoozeSeq = 0
        inst.kind = "TIMER"
        XCTAssertFalse(Engine.isRegeneratedByExpand(inst, calendarReadable: true))
    }

    private func soundFile(_ name: String) -> String {
        AlarmSoundLibrary.soundsDirectory.appendingPathComponent(name).path
    }

    private func placeSound() -> String {
        let name = "\(AlarmSoundLibrary.prefix)test-\(UUID().uuidString.lowercased()).caf"
        FileManager.default.createFile(atPath: soundFile(name), contents: Data([0]))
        return name
    }

    /// 同じ保存先を使う2つ目の Engine (バックグラウンドの resync と同じ作り)
    private func backgroundEngine() -> Engine {
        Engine(scheduler: FakeScheduler(), store: Store(fileURL: storeURL))
    }

    private var storeURL: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("shared-store-\(name.hashValue).json")
    }

    /// 古い写しのバックグラウンド Engine が片付けても、写しを読んだ後に取り込んだ音は消さない
    func testStaleEngineKeepsSoundImportedAfterItLoaded() async throws {
        let foreground = Engine(scheduler: FakeScheduler(), store: Store(fileURL: storeURL))
        let background = backgroundEngine()
        try? await Task.sleep(nanoseconds: 20_000_000)
        let fresh = placeSound()
        defer { AlarmSoundLibrary.remove(fresh); try? FileManager.default.removeItem(at: storeURL) }

        background.removeUnusedSounds()

        XCTAssertTrue(AlarmSoundLibrary.exists(fresh), "前景で取り込んだばかりの音を消した")
        _ = foreground
    }

    /// 写しより前からある音でも、前景が後から既定にして保存していれば消さない
    func testStaleEngineKeepsSoundTheOtherEngineSaved() async throws {
        let older = placeSound()
        defer { AlarmSoundLibrary.remove(older); try? FileManager.default.removeItem(at: storeURL) }
        try? await Task.sleep(nanoseconds: 20_000_000)
        let foreground = Engine(scheduler: FakeScheduler(), store: Store(fileURL: storeURL))
        let background = backgroundEngine()
        foreground.store.setAlarmSound(file: older, label: "test")
        _ = foreground.store.savedState()

        background.removeUnusedSounds()

        XCTAssertTrue(AlarmSoundLibrary.exists(older), "前景の既定の音を古い写しで消した")
    }

    /// 片付け自体は止めていない: どこからも指されていない古い音は消える
    func testUnusedOldSoundIsStillRemoved() async throws {
        let unused = placeSound()
        defer { AlarmSoundLibrary.remove(unused); try? FileManager.default.removeItem(at: storeURL) }
        try? await Task.sleep(nanoseconds: 20_000_000)
        let background = backgroundEngine()

        background.removeUnusedSounds()

        XCTAssertFalse(AlarmSoundLibrary.exists(unused), "使われていない音が残った")
    }
}
