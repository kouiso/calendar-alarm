import XCTest
@testable import iosApp

/// 同梱の音 (#147) の Library/Sounds へのコピーと、設定の札を押した時の入れ直しを確かめる。音は鳴らさない
final class BundledSoundPresetCopyTests: XCTestCase {
    private func url(_ name: String) -> URL {
        AlarmSoundLibrary.soundsDirectory.appendingPathComponent(name)
    }

    override func setUp() {
        // 前の試験や起動で置かれた分を消し、コピーを一から確かめる
        for name in AlarmSoundLibrary.bundledPresets { try? FileManager.default.removeItem(at: url(name)) }
    }

    override func tearDown() {
        // アプリは起動時に必ず置くので、試験の後も置いた状態に戻す
        AlarmSoundLibrary.installBundledPresets()
    }

    /// 3 つとも Library/Sounds に置かれ、中身は同梱の .caf と同じ
    func testInstallCopiesEveryPresetFromBundle() throws {
        AlarmSoundLibrary.installBundledPresets()
        for name in AlarmSoundLibrary.bundledPresets {
            XCTAssertTrue(AlarmSoundLibrary.exists(name), "\(name) が Library/Sounds に無い")
            let src = try XCTUnwrap(Bundle.main.url(forResource: name, withExtension: nil), "\(name) が同梱されていない")
            XCTAssertEqual(try Data(contentsOf: url(name)), try Data(contentsOf: src))
        }
    }

    /// 何度コピーしても同じ結果: 2 回目以降はファイルの数も中身も変わらない
    func testInstallingRepeatedlyGivesSameResult() throws {
        AlarmSoundLibrary.installBundledPresets()
        let first = Dictionary(uniqueKeysWithValues: try AlarmSoundLibrary.bundledPresets.map { ($0, try Data(contentsOf: url($0))) })
        let namesAfterFirst = Set(try FileManager.default.contentsOfDirectory(atPath: AlarmSoundLibrary.soundsDirectory.path))
        for _ in 0..<3 { AlarmSoundLibrary.installBundledPresets() }
        let namesAfterMany = Set(try FileManager.default.contentsOfDirectory(atPath: AlarmSoundLibrary.soundsDirectory.path))
        XCTAssertEqual(namesAfterFirst, namesAfterMany)
        for name in AlarmSoundLibrary.bundledPresets {
            XCTAssertEqual(try Data(contentsOf: url(name)), first[name])
        }
    }

    /// すでにあるファイルは上書きしない (予約が読んでいる最中の音を差し替えない)
    func testInstallKeepsExistingFile() throws {
        let name = try XCTUnwrap(AlarmSoundLibrary.bundledPresets.first)
        try FileManager.default.createDirectory(at: AlarmSoundLibrary.soundsDirectory, withIntermediateDirectories: true)
        let marker = Data("existing".utf8)
        try marker.write(to: url(name))
        AlarmSoundLibrary.installBundledPresets()
        XCTAssertEqual(try Data(contentsOf: url(name)), marker)
    }

    /// 取り込み音の掃除 (removeAll) で、同梱の音は消えない
    func testCleanupOfImportedSoundsKeepsPresets() {
        AlarmSoundLibrary.installBundledPresets()
        AlarmSoundLibrary.removeAll(except: [])
        for name in AlarmSoundLibrary.bundledPresets {
            XCTAssertTrue(AlarmSoundLibrary.exists(name), "\(name) が掃除で消えた")
        }
    }

    /// 札と表示名: 標準は nil、プリセットはそのファイル名と日本語の名前
    func testChipMapsToFileAndLabel() {
        let standard = SettingsView.soundChange(forChip: "__default__")
        XCTAssertNil(standard.file)
        XCTAssertNil(standard.label)
        for name in AlarmSoundLibrary.bundledPresets {
            let change = SettingsView.soundChange(forChip: name)
            XCTAssertEqual(change.file, name)
            XCTAssertEqual(change.label, SettingsView.soundPresetLabel(name))
            XCTAssertNotEqual(change.label, name, "\(name) に表示名が無い")
        }
    }
}

/// 札を押す → 保存した音の変更 → 入れ直し、の通し (設定の changeSound と同じ呼び出し)
@MainActor
final class BundledSoundPresetRebookTests: XCTestCase {
    private var engine: Engine!
    private var fake: FakeScheduler!

    override func setUp() async throws {
        fake = FakeScheduler()
        engine = Engine(scheduler: fake, store: Store(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("store-\(UUID().uuidString).json")))
        engine.store.mutate {
            $0.scheduled = [:]
            $0.standaloneAlarms = []
            $0.alarmSoundFile = nil
        }
        AlarmSoundLibrary.currentDefault = nil
        AlarmSoundLibrary.installBundledPresets()
    }

    override func tearDown() async throws {
        engine.store.mutate {
            $0.scheduled = [:]
            $0.standaloneAlarms = []
            $0.alarmSoundFile = nil
        }
        AlarmSoundLibrary.currentDefault = nil
    }

    /// SettingsView.changeSound と同じ手順 (保存 → 入れ直し)
    private func tap(chip: String) async {
        let change = SettingsView.soundChange(forChip: chip)
        engine.store.setAlarmSound(file: change.file, label: change.label)
        await engine.rescheduleForSoundChange()
    }

    private func bookAlarm() async throws -> String {
        let at = Date().addingTimeInterval(3 * 3600)
        let c = Calendar.current.dateComponents([.hour, .minute], from: at)
        let aid = engine.store.upsertAlarm(StandaloneAlarmDTO(hour: c.hour!, minute: c.minute!, repeatMode: "ONCE"))
        await engine.resync(reason: "test setup")
        let rec = try XCTUnwrap(engine.store.state.scheduled.values.first { $0.instance.standaloneAlarmId == aid })
        XCTAssertNotNil(fake.booked[rec.instance.id])
        XCTAssertNil(fake.booked[rec.instance.id]?.sound)
        return rec.instance.id
    }

    /// プリセットの札を押すと、入っている予約がその音で入れ直される。標準に戻すと既定音で入れ直される
    func testChipTapRebooksReservationWithPresetThenDefault() async throws {
        let id = try await bookAlarm()
        for name in AlarmSoundLibrary.bundledPresets {
            await tap(chip: name)
            XCTAssertEqual(engine.store.state.alarmSoundFile, name)
            XCTAssertEqual(fake.booked[id]?.sound, name, "\(name) で入れ直していない")
            XCTAssertEqual(engine.store.state.scheduled[id]?.soundFile, name)
            XCTAssertEqual(engine.store.state.scheduled[id]?.state, .pending)
        }
        await tap(chip: "__default__")
        XCTAssertNil(engine.store.state.alarmSoundFile)
        XCTAssertNil(fake.booked[id]?.sound, "標準に戻したのに前の音のまま")
        XCTAssertNil(engine.store.state.scheduled[id]?.soundFile)
    }
}
