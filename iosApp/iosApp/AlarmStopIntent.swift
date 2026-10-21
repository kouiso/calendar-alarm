import Foundation
import AppIntents
#if canImport(AlarmKit)
import AlarmKit
#endif

/// AlarmKit の停止ボタンで呼ばれる intent (#61)。
/// アプリが終了・サスペンド中でも OS がアプリの処理として呼ぶので、ここで「止めた」を書き残す。
/// 書き残しが無いと、resync は AlarmKit から消えた理由 (止めた / 予約が消えた) を見分けられない。
@available(iOS 26.0, *)
struct AlarmStopIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "アラームを止める"
    static var description = IntentDescription("鳴っているアラームを止めて、止めたことを記録します")
    static var isDiscoverable: Bool = false

    @Parameter(title: "instanceId")
    var instanceId: String

    init(instanceId: String) {
        self.instanceId = instanceId
    }

    init() {
        self.instanceId = ""
    }

    func perform() async throws -> some IntentResult {
        guard !instanceId.isEmpty else { return .result() }
        // 先に書き残してから止める。止めた後に resync が走っても、消えた理由が分かるようにするため
        OffAppStopLog().record(instanceId, atMillis: Int64(Date().timeIntervalSince1970 * 1000))
        // 停止ボタンなので OS 側で既に止まっていることが多い。止まっていれば stop は投げるだけで害は無い
        try? AlarmManager.shared.stop(id: AlarmKitScheduler.uuid(from: instanceId))
        return .result()
    }
}
