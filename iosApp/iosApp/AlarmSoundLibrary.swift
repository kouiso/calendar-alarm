import Foundation
import AVFoundation

/// アラーム音の取り込みと選択 (#101)。
/// iPhone のアラーム機能 (AlarmKit) でアプリが指定できるのは音だけで、音量・だんだん大きく・バイブは
/// 指定できない (`AlarmManager.AlarmConfiguration` の引数は sound だけ。iOS 26.1 SDK で確認)。
/// 音は `AlertConfiguration.AlertSound.named(_:)` に渡すため Library/Sounds に置く
/// (通知で鳴らす iOS 26 未満も `UNNotificationSoundName` で同じ場所を読む)。
enum AlarmSoundLibrary {
    /// 通知音と同じ上限。これを超える音は OS が既定音に差し替えるので、取り込み時に切る
    static let maxSeconds: Double = 30
    /// 取り込んだ音のファイル名の頭。Library/Sounds の他のファイルと区別する
    static let prefix = "alarm-custom-"

    /// 今の既定の音 (ファイル名)。Store が読み込みと変更の時に入れる。
    /// 予約側 (AlarmKit/通知) は Store を持たないので、ここを見る
    static var currentDefault: String?

    static var soundsDirectory: URL {
        FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Sounds", isDirectory: true)
    }

    static func exists(_ name: String) -> Bool {
        FileManager.default.fileExists(atPath: soundsDirectory.appendingPathComponent(name).path)
    }

    /// 予約に使う音の名前。アラーム個別の指定 → 既定の順で、ファイルが無ければ nil (= OS の既定音)。
    /// Android 形式の content:// などはファイル名として存在しないので既定音に落ちる
    static func soundName(for inst: AlarmInstanceDTO) -> String? {
        for candidate in [inst.soundUri, currentDefault] {
            if let name = candidate, !name.isEmpty, !name.contains("/"), exists(name) { return name }
        }
        return nil
    }

    /// 選ばれた音声ファイルを Library/Sounds へ caf (リニア PCM) で書き出し、ファイル名を返す。
    /// mp3・m4a などは AVAudioFile が読める形式ならそのまま取り込める。30秒を超える分は切る
    static func importSound(from source: URL, now: Date = Date()) throws -> String {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        try FileManager.default.createDirectory(at: soundsDirectory, withIntermediateDirectories: true)

        let input = try AVAudioFile(forReading: source)
        let format = input.processingFormat
        let name = "\(prefix)\(Int(now.timeIntervalSince1970)).caf"
        let dest = soundsDirectory.appendingPathComponent(name)
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: format.sampleRate,
            AVNumberOfChannelsKey: format.channelCount,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
        ]
        do {
            let output = try AVAudioFile(forWriting: dest, settings: settings,
                                         commonFormat: format.commonFormat, interleaved: format.isInterleaved)
            let maxFrames = AVAudioFramePosition(format.sampleRate * maxSeconds)
            let total = min(input.length, maxFrames)
            let chunk: AVAudioFrameCount = 32_768
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: chunk) else {
                throw CocoaError(.fileReadCorruptFile)
            }
            var written: AVAudioFramePosition = 0
            while written < total {
                let want = AVAudioFrameCount(min(AVAudioFramePosition(chunk), total - written))
                try input.read(into: buffer, frameCount: want)
                if buffer.frameLength == 0 { break }
                try output.write(from: buffer)
                written += AVAudioFramePosition(buffer.frameLength)
            }
            guard written > 0 else { throw CocoaError(.fileReadCorruptFile) }
        } catch {
            // 途中で失敗した書きかけは残さない (選べない音が一覧に残るのを防ぐ)
            try? FileManager.default.removeItem(at: dest)
            throw error
        }
        return name
    }

    /// 取り込んだ音を消す。取り込み以外のファイルは消さない
    static func remove(_ name: String) {
        guard name.hasPrefix(prefix), !name.contains("/") else { return }
        try? FileManager.default.removeItem(at: soundsDirectory.appendingPathComponent(name))
    }
}
