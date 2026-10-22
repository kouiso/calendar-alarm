import XCTest
@testable import iosApp

/// 鳴動画面のチップで既定と違う分数を選んだ時のスヌーズ (#52)。
/// AlarmKit の countdown は予約時の分数から変えられないので、子の予約 (id:snz1) で鳴らし直す
@MainActor
final class EngineSnoozeMinutesTests: XCTestCase {
    private var engine: Engine!
    private var fake: FakeScheduler!

    override func setUp() async throws {
        fake = FakeScheduler()
        // 端末の保存データを上書きしないよう、一時ファイルの Store を渡す
        engine = Engine(scheduler: fake, store: Store(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("store-\(UUID().uuidString).json")))
        engine.store.mutate {
            $0.scheduled = [:]
            $0.standaloneAlarms = []
        }
    }

    override func tearDown() async throws {
        engine.store.mutate {
            $0.scheduled = [:]
            $0.standaloneAlarms = []
        }
    }

    /// 2時間後の単発アラームを resync で予約し、鳴った (fired) ところにする
    private func ringOneShot() async throws -> (alarmId: Int64, inst: AlarmInstanceDTO) {
        let at = Date().addingTimeInterval(2 * 3600)
        let c = Calendar.current.dateComponents([.hour, .minute], from: at)
        let aid = engine.store.upsertAlarm(StandaloneAlarmDTO(hour: c.hour!, minute: c.minute!, repeatMode: "ONCE"))
        await engine.resync(reason: "test setup")
        let rec = try XCTUnwrap(engine.store.state.scheduled.values.first { $0.instance.standaloneAlarmId == aid })
        engine.store.markState(rec.instance.id, .fired)
        return (aid, rec.instance)
    }

    private func alarmEnabled(_ aid: Int64) -> Bool? {
        engine.store.state.standaloneAlarms.first { $0.id == aid }?.enabled
    }

    /// 既定 (10分) と違う5分を選ぶと、5分後の子を予約して親はスヌーズ中にする
    func testDifferentMinutesBooksChildAfterThoseMinutes() async throws {
        let (aid, inst) = try await ringOneShot()
        XCTAssertEqual(inst.snoozeMinutes, 10)
        let before = Engine.nowMillis

        await engine.snooze(inst, minutes: 5)?.value

        let childId = "\(inst.id):snz1"
        let child = try XCTUnwrap(fake.booked[childId]?.inst, "子が OS に予約されていない")
        XCTAssertEqual(child.snoozeMinutes, 5, "子の分数が選んだ分数になっていない")
        XCTAssertEqual(child.snoozeSeq, 1)
        XCTAssertGreaterThanOrEqual(child.triggerAtMillis, before + 5 * 60_000)
        XCTAssertLessThan(child.triggerAtMillis, before + 5 * 60_000 + 10_000, "5分後に鳴る予約になっていない")
        XCTAssertEqual(engine.store.state.scheduled[childId]?.state, .pending)
        XCTAssertEqual(engine.store.state.scheduled[childId]?.alarmKitId, "fake-\(childId)")
        XCTAssertEqual(engine.store.state.scheduled[inst.id]?.state, .snoozed)
        XCTAssertTrue(engine.store.state.audit.contains { $0.type == "SNOOZE" && $0.detail == "\(inst.id) +5m → \(childId)" })
        XCTAssertEqual(alarmEnabled(aid), true, "子が鳴る前に単発アラームが OFF になった")
    }

    /// 子の記録は OS への予約を待たずに入る。予約の await 中に同期処理が走っても、
    /// 親を「止められた単発」と見なさない (Engine.reconcileAlarmKitState の判定)
    func testChildRecordExistsWhileBookingIsInFlight() async throws {
        let (_, inst) = try await ringOneShot()
        let childId = "\(inst.id):snz1"
        var seenDuringBooking: ScheduledRecord?
        var pendingDuringBooking = false
        fake.duringSchedule = { [engine] in
            guard let engine else { return }
            seenDuringBooking = engine.store.state.scheduled[childId]
            pendingDuringBooking = Engine.hasPendingSnoozeChild(of: inst.id, in: engine.store.state.scheduled)
        }

        let task = engine.snooze(inst, minutes: 15)
        XCTAssertEqual(engine.store.state.scheduled[childId]?.state, .pending, "呼んだ直後に子の記録が無い")
        XCTAssertNil(engine.store.state.scheduled[childId]?.alarmKitId)
        await task?.value

        XCTAssertEqual(seenDuringBooking?.state, .pending)
        XCTAssertTrue(pendingDuringBooking)
        XCTAssertEqual(fake.booked[childId]?.inst.snoozeMinutes, 15)
    }

    /// 子が鳴る前の resync は子を残し、単発アラームも ON のまま
    func testResyncKeepsChildAndOneShotWhileChildIsPending() async throws {
        let (aid, inst) = try await ringOneShot()
        await engine.snooze(inst, minutes: 30)?.value
        let childId = "\(inst.id):snz1"

        await engine.resync(reason: "after snooze")

        XCTAssertEqual(engine.store.state.scheduled[childId]?.state, .pending, "resync が子を捨てた")
        XCTAssertNotNil(fake.booked[childId], "resync 後に子の予約が OS から消えた")
        XCTAssertEqual(alarmEnabled(aid), true)
    }

    /// 子を止めた時に初めて単発アラームを消費 (OFF) にする
    func testStoppingChildTurnsOneShotOff() async throws {
        let (aid, inst) = try await ringOneShot()
        await engine.snooze(inst, minutes: 5)?.value
        let childId = "\(inst.id):snz1"
        let child = try XCTUnwrap(engine.store.state.scheduled[childId]?.instance)
        engine.store.markState(childId, .fired)

        engine.dismiss(child)

        XCTAssertEqual(engine.store.state.scheduled[childId]?.state, .dismissed)
        XCTAssertEqual(alarmEnabled(aid), false, "子を止めても単発アラームが ON のまま")
        XCTAssertFalse(Engine.hasPendingSnoozeChild(of: inst.id, in: engine.store.state.scheduled))
    }

    /// 既定と同じ分数を選んだ時は既定のスヌーズと同じ道を通る
    func testSameMinutesAsDefaultUsesDefaultSnooze() async throws {
        let (_, inst) = try await ringOneShot()

        await engine.snooze(inst, minutes: inst.snoozeMinutes)?.value

        // 偽物の予約は AlarmKit ではないので、既定のスヌーズも子の予約になる
        XCTAssertEqual(fake.booked["\(inst.id):snz1"]?.inst.snoozeMinutes, 10)
        XCTAssertTrue(engine.store.state.audit.contains { $0.detail == "\(inst.id) +10m → \(inst.id):snz1" })
    }

    /// 子の判定は「その id の子」で「まだ鳴る前」のものだけを数える
    func testPendingSnoozeChildMatchesOnlyOwnPendingChildren() {
        func rec(_ id: String, _ state: InstanceState) -> ScheduledRecord {
            ScheduledRecord(instance: AlarmInstanceDTO(id: id, triggerAtMillis: 0, title: "t", kind: "ALARM"),
                            state: state, alarmKitId: nil)
        }
        XCTAssertFalse(Engine.hasPendingSnoozeChild(of: "sa:1:x", in: ["sa:1:x": rec("sa:1:x", .snoozed)]))
        XCTAssertTrue(Engine.hasPendingSnoozeChild(of: "sa:1:x", in: ["sa:1:x:snz1": rec("sa:1:x:snz1", .pending)]))
        XCTAssertFalse(Engine.hasPendingSnoozeChild(of: "sa:1:x", in: ["sa:1:x:snz1": rec("sa:1:x:snz1", .dismissed)]))
        XCTAssertFalse(Engine.hasPendingSnoozeChild(of: "sa:1:x", in: ["sa:1:x2:snz1": rec("sa:1:x2:snz1", .pending)]))
    }
}

/// スライド停止の指を離した後の動き (#52)
final class SlideToStopLogicTests: XCTestCase {
    /// 8割に届かずに離したら止めず、丸を左端へ戻す
    func testShortDragDoesNotStop() {
        var calls = 0
        XCTAssertEqual(SlideToStopLogic.releasedProgress(0.79) { calls += 1; return true }, 0)
        XCTAssertEqual(calls, 0, "8割未満で停止を呼んだ")
    }

    /// 8割以上で止められたら丸は右端に残る
    func testFullDragStops() {
        var calls = 0
        XCTAssertEqual(SlideToStopLogic.releasedProgress(0.8) { calls += 1; return true }, 1)
        XCTAssertEqual(calls, 1)
    }

    /// 止められなかった (鳴り続けている) 時は丸を左端へ戻し、もう一度引けるようにする
    func testFailedStopReturnsThumbToLeftEdge() {
        var calls = 0
        XCTAssertEqual(SlideToStopLogic.releasedProgress(1) { calls += 1; return false }, 0)
        XCTAssertEqual(calls, 1)
        // 戻った丸をもう一度引けば、今度は止まる
        XCTAssertEqual(SlideToStopLogic.releasedProgress(0.95) { true }, 1)
    }

    /// 掴める幅は Android と同じ「丸 64 + 余白 8 × 3 = 88」まで
    func testGrabWidthMatchesSharedRule() {
        XCTAssertTrue(SlideToStopLogic.isGrab(startX: 88, thumbSize: 64, pad: 8))
        XCTAssertFalse(SlideToStopLogic.isGrab(startX: 88.5, thumbSize: 64, pad: 8))
    }
}

/// 低い窓では「スヌーズ時間を選ぶ」を出さない。出すと下に固定した操作群が高くなり、停止の丸が窓の外へ出る
final class RingingLayoutTests: XCTestCase {
    func testPresetPickerNeedsEnoughWindowHeight() {
        // iPhone SE (667pt) でも、ふつうの窓では出す
        XCTAssertTrue(RingingLayout.showsPresetPicker(windowHeight: 667))
        XCTAssertTrue(RingingLayout.showsPresetPicker(windowHeight: RingingLayout.minHeightForPresetPicker))
        // 小さいウィンドウ (iPad の Stage Manager など) では出さない
        XCTAssertFalse(RingingLayout.showsPresetPicker(windowHeight: RingingLayout.minHeightForPresetPicker - 1))
        XCTAssertFalse(RingingLayout.showsPresetPicker(windowHeight: 320))
    }

    /// 開いたチップ込みの固定の操作群 (約 290pt) を足しても、上のスクロール領域に 230pt 以上残る
    func testLeavesRoomForTimeAboveTheControls() {
        XCTAssertGreaterThanOrEqual(RingingLayout.minHeightForPresetPicker - 290, 230)
    }
}
