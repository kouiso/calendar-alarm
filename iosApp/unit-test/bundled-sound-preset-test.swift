import XCTest
@testable import iosApp

/// 同梱の音 (#147) の Library/Sounds へのコピーと表示名、一覧で選んだ時の入れ直しを確かめる。音は鳴らさない。
/// このクラスは MainActor に縛られない AlarmSoundLibrary だけを呼ぶので、@MainActor を付けない
final class BundledSoundPresetCopyTests: XCTestCase {
    private func url(_ name: String) -> URL {
        AlarmSoundLibrary.soundsDirectory.appendingPathComponent(name)
    }

    override func setUp() {
        // 前の試験や起動で置かれた分を消し、コピーを一から確かめる
        for name in AlarmSoundLibrary.bundledPresets { try? FileManager.default.removeItem(at: url(name)) }
    }

    override func tearDown() {
        // アプリは起動時に必ず置くので、試験の後も置いた状態に戻す。
        // 大きさだけ同じ偽物を試験が置くことがあるので、一度消してから置き直す
        for name in AlarmSoundLibrary.bundledPresets { try? FileManager.default.removeItem(at: url(name)) }
        AlarmSoundLibrary.installBundledPresets()
    }

    /// 3 つとも Library/Sounds に置かれ、中身は同梱の .caf と同じ
    func testInstallCopiesEveryPresetFromBundle() throws {
        XCTAssertEqual(AlarmSoundLibrary.installBundledPresets(), [], "置けなかった音を返した")
        XCTAssertEqual(AlarmSoundLibrary.installedPresets, AlarmSoundLibrary.bundledPresets)
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

    /// 大きさが同梱の物と違うファイル (書きかけ・前の版) は、同梱の中身で置き直す
    func testInstallReplacesFileWhoseSizeDiffers() throws {
        let name = try XCTUnwrap(AlarmSoundLibrary.bundledPresets.first)
        try FileManager.default.createDirectory(at: AlarmSoundLibrary.soundsDirectory, withIntermediateDirectories: true)
        try Data("truncated".utf8).write(to: url(name))
        XCTAssertEqual(AlarmSoundLibrary.installBundledPresets(), [])
        let src = try XCTUnwrap(Bundle.main.url(forResource: name, withExtension: nil))
        XCTAssertEqual(try Data(contentsOf: url(name)), try Data(contentsOf: src))
        XCTAssertTrue(AlarmSoundLibrary.installedPresets.contains(name))
    }

    /// 大きさが同じファイルは置き直さない。起動のたびに書き直さず、比べるのは大きさだけ
    func testInstallKeepsFileOfSameSize() throws {
        let name = try XCTUnwrap(AlarmSoundLibrary.bundledPresets.first)
        let src = try XCTUnwrap(Bundle.main.url(forResource: name, withExtension: nil))
        let size = try Data(contentsOf: src).count
        try FileManager.default.createDirectory(at: AlarmSoundLibrary.soundsDirectory, withIntermediateDirectories: true)
        let sameSize = Data(repeating: 0, count: size)
        try sameSize.write(to: url(name))
        AlarmSoundLibrary.installBundledPresets()
        XCTAssertEqual(try Data(contentsOf: url(name)), sameSize)
    }

    /// Library/Sounds に無い同梱の音は一覧に出さず、選んであっても既定音 (nil) として扱う。
    /// 予約も soundName で既定音に落ちるので、画面と実際に鳴る音を揃える
    func testMissingPresetIsHiddenAndTreatedAsDefault() throws {
        AlarmSoundLibrary.installBundledPresets()
        let missing = try XCTUnwrap(AlarmSoundLibrary.bundledPresets.first)
        let present = try XCTUnwrap(AlarmSoundLibrary.bundledPresets.last)
        try FileManager.default.removeItem(at: url(missing))
        XCTAssertFalse(AlarmSoundLibrary.installedPresets.contains(missing))
        XCTAssertTrue(AlarmSoundLibrary.installedPresets.contains(present))
        XCTAssertNil(AlarmSoundLibrary.effectiveSound(missing))
        XCTAssertEqual(AlarmSoundLibrary.effectiveSound(present), present)
        XCTAssertNil(AlarmSoundLibrary.effectiveSound(nil))
    }

    /// 大きさが同梱の物と違うまま残った音 (置き直しに失敗した書きかけ) は、ファイルがあっても一覧に出さず、
    /// 選んであっても既定音として扱う。予約も既定音に落とす
    func testPresetOfWrongSizeIsHiddenAndTreatedAsDefault() throws {
        AlarmSoundLibrary.installBundledPresets()
        let broken = try XCTUnwrap(AlarmSoundLibrary.bundledPresets.first)
        try Data("truncated".utf8).write(to: url(broken))
        XCTAssertTrue(AlarmSoundLibrary.exists(broken))
        XCTAssertFalse(AlarmSoundLibrary.usable(broken))
        XCTAssertFalse(AlarmSoundLibrary.installedPresets.contains(broken))
        XCTAssertNil(AlarmSoundLibrary.effectiveSound(broken))
        let previous = AlarmSoundLibrary.currentDefault
        defer { AlarmSoundLibrary.currentDefault = previous }
        AlarmSoundLibrary.currentDefault = broken
        let inst = AlarmInstanceDTO(id: "t", triggerAtMillis: 0, title: "t", kind: "TIMER")
        XCTAssertNil(AlarmSoundLibrary.soundName(for: inst))
    }

    /// 取り込み音の掃除 (removeAll) は取り込み音を消し、同梱の音は消さない。
    /// 消すのは試験で置いた 1 つだけ。ホストのアプリに取り込んである本物の音は keep に入れて残す
    func testCleanupOfImportedSoundsKeepsPresets() throws {
        AlarmSoundLibrary.installBundledPresets()
        let imported = "\(AlarmSoundLibrary.prefix)test-\(UUID().uuidString.lowercased()).caf"
        defer { AlarmSoundLibrary.remove(imported) }
        try Data("imported".utf8).write(to: url(imported))
        XCTAssertTrue(AlarmSoundLibrary.exists(imported))
        let hostSounds = Set(try FileManager.default.contentsOfDirectory(atPath: AlarmSoundLibrary.soundsDirectory.path)
            .filter { $0.hasPrefix(AlarmSoundLibrary.prefix) && $0 != imported })
        AlarmSoundLibrary.removeAll(except: hostSounds)
        XCTAssertFalse(AlarmSoundLibrary.exists(imported), "取り込み音が掃除で消えていない")
        for name in AlarmSoundLibrary.bundledPresets {
            XCTAssertTrue(AlarmSoundLibrary.exists(name), "\(name) が掃除で消えた")
        }
        for name in hostSounds {
            XCTAssertTrue(AlarmSoundLibrary.exists(name), "\(name) (ホストの取り込み音) が消えた")
        }
    }

    /// 名前は表示のたびに引く。同梱の音は保存した名前 (選んだ時の言語) より今の言語の名前を出す。
    /// 標準とファイルの無い音は「iPhone の標準」、取り込み音は保存した名前
    func testDisplayNameIsResolvedAtDisplayTime() throws {
        AlarmSoundLibrary.installBundledPresets()
        let standard = String(localized: "iPhone の標準")
        XCTAssertEqual(AlarmSoundLibrary.displayName(file: nil, label: nil), standard)
        for name in AlarmSoundLibrary.bundledPresets {
            let label = try XCTUnwrap(AlarmSoundLibrary.presetLabel(name), "\(name) に表示名が無い")
            XCTAssertNotEqual(label, name)
            XCTAssertEqual(AlarmSoundLibrary.displayName(file: name, label: nil), label)
            XCTAssertEqual(AlarmSoundLibrary.displayName(file: name, label: "name saved in another language"), label)
        }
        let imported = "\(AlarmSoundLibrary.prefix)test-\(UUID().uuidString.lowercased()).caf"
        defer { AlarmSoundLibrary.remove(imported) }
        XCTAssertNil(AlarmSoundLibrary.presetLabel(imported))
        XCTAssertEqual(AlarmSoundLibrary.displayName(file: imported, label: "My tone"), standard, "ファイルの無い音を標準と出していない")
        try Data("imported".utf8).write(to: url(imported))
        XCTAssertEqual(AlarmSoundLibrary.displayName(file: imported, label: "My tone"), "My tone")
    }
}

/// 一覧で選ぶ → 保存した音の変更 → 入れ直し、の通し (アラーム音の画面と同じ Engine.changeDefaultSound を通す)
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

    /// アラーム音の画面で 1 行を選んだ時と同じ呼び出し。nil は「iPhone の標準」。同梱の音は名前を保存しない
    private func choose(_ file: String?) async {
        await engine.changeDefaultSound(file: file, label: nil)
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

    /// 同梱の音を選ぶと、入っている予約がその音で入れ直される。「iPhone の標準」を選ぶと既定音で入れ直される
    func testChoosingPresetRebooksReservationThenDefault() async throws {
        let id = try await bookAlarm()
        for name in AlarmSoundLibrary.bundledPresets {
            await choose(name)
            XCTAssertEqual(engine.store.state.alarmSoundFile, name)
            XCTAssertNil(engine.store.state.alarmSoundLabel, "同梱の音の名前を保存した")
            XCTAssertEqual(fake.booked[id]?.sound, name, "\(name) で入れ直していない")
            XCTAssertEqual(engine.store.state.scheduled[id]?.soundFile, name)
            XCTAssertEqual(engine.store.state.scheduled[id]?.state, .pending)
        }
        await choose(nil)
        XCTAssertNil(engine.store.state.alarmSoundFile)
        XCTAssertNil(fake.booked[id]?.sound, "標準に戻したのに前の音のまま")
        XCTAssertNil(engine.store.state.scheduled[id]?.soundFile)
    }
}
