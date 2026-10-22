import Foundation
import SwiftUI
import CryptoKit
import UserNotifications
import AlarmKit
import ActivityKit

/// 鳴動予約の抽象。実体は AlarmKit (iOS26+) または UNUserNotificationCenter。

/// 予約側が「未予約なのに成功扱い」になるのを防ぐ失敗理由。
/// nil 返却だと呼び出し側が .pending 記録だけ残してゾンビ予約化するため
/// 失敗は必ず throw で表す。
enum SchedulerError: LocalizedError {
    case notAuthorized
    var errorDescription: String? { "アラーム権限がありません" }
}

protocol AlarmScheduler {
    /// true = AlarmKit の本物アラーム経路が使える
    var usesRealAlarms: Bool { get }
    var authorizationGranted: Bool { get }
    /// まだ一度も頼んでいない (OS のダイアログで頼める) か
    var authorizationUndetermined: Bool { get }
    func requestAuthorization() async
    /// [sound] は Library/Sounds の取り込み音のファイル名 (nil = 既定音)。
    /// 呼び出し側が決めて記録するので、予約側で選び直さない
    /// @returns AlarmKit UUID など予約側の識別子 (通知経路は nil)
    func schedule(_ inst: AlarmInstanceDTO, sound: String?) async throws -> String?
    func cancel(instanceId: String, reservationId: String?) async
    /// OS に今入っている予約の UUID。許可が無い・一覧を読めない・予約を一覧できない経路では nil。
    /// nil の時に空として扱うと、全部「消えた」と誤判定するため分ける (#61)
    var liveReservationIdsIfReadable: Set<UUID>? { get }
    /// OS にこのインスタンスの予約がまだ残っているか
    func hasReservation(instanceId: String) async -> Bool
}

/// インスタンス id から決める予約の UUID。AlarmKit の型に置くと iOS 26 未満の分岐から呼べないので外に置く
enum ReservationUUID {
    /// AlarmKit は UUID 必須。差分適用のため id から決定的に作る
    static func from(_ instanceId: String) -> UUID {
        var bytes = [UInt8](Insecure.MD5.hash(data: Data(instanceId.utf8)))
        bytes[6] = (bytes[6] & 0x0F) | 0x40
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        let t = uuid_t(bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                       bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15])
        return UUID(uuid: t)
    }
}

// MARK: - AlarmKit (iOS 26+)

struct AppAlarmMetadata: AlarmMetadata {
    var instanceId: String
    var title: String
    var snoozeMinutes: Int
}

@available(iOS 26.0, *)
final class AlarmKitScheduler: AlarmScheduler {

    /// AlarmKit 上限 (~64) に対し、単発/タイマー/スヌーズ分の余裕を残す
    static let maxCapacity = 48

    private let manager = AlarmManager.shared

    var usesRealAlarms: Bool { true }
    var authorizationGranted: Bool { manager.authorizationState == .authorized }
    var authorizationUndetermined: Bool { manager.authorizationState == .notDetermined }

    func requestAuthorization() async {
        _ = try? await manager.requestAuthorization()
    }

    /// AlarmKit 側に既に予約されているアラームの UUID 集合。
    var existingIds: Set<UUID> {
        let list: [Alarm] = (try? manager.alarms) ?? []
        return Set(list.map { $0.id })
    }

    /// 鳴動中 (alerting) のアラームを返す。
    var alertingAlarms: [Alarm] {
        let list: [Alarm] = (try? manager.alarms) ?? []
        return list.filter { $0.state == .alerting }
    }

    /// 全アラーム (id, state) のスナップショット。
    var allAlarms: [Alarm] { (try? manager.alarms) ?? [] }

    /// 読めた時だけ全アラームを返す。読めない時に空として扱うと、全部「止められた」と誤判定するため分ける
    var allAlarmsIfReadable: [Alarm]? { try? manager.alarms }

    var liveReservationIdsIfReadable: Set<UUID>? {
        guard authorizationGranted, let live = allAlarmsIfReadable else { return nil }
        return Set(live.map { $0.id })
    }

    /// 鳴動中アラームを停止。
    func stop(uuid: UUID) throws { try manager.stop(id: uuid) }

    /// 指定 UUID の AlarmKit 側の現在状態 (居なければ nil)。
    func state(of uuid: UUID) -> Alarm.State? {
        allAlarms.first { $0.id == uuid }?.state
    }

    /// 鳴動中アラームを OS 側が静かになるまで止める。
    /// stop() は alerting 中しか効かない (countdown 中に投げると例外) ので、
    /// 状態を見て stop → cancel の順にエスカレートし、結果を実状態で確認する。
    /// @returns true = もう鳴っていない (or そもそも居ない)。false = まだ鳴動中。
    func silence(uuid: UUID) -> Bool {
        switch state(of: uuid) {
        case .none:
            return true
        case .alerting:
            try? manager.stop(id: uuid)
        default:
            // countdown/一時停止/他状態: 「停止」= 予約ごとキャンセル。
            // stop() は alerting 以外で必ず投げるので cancel に倒す。
            try? manager.cancel(id: uuid)
        }
        if state(of: uuid) == .alerting { try? manager.cancel(id: uuid) }
        return state(of: uuid) != .alerting
    }

    /// 鳴動中アラームを postAlert カウントダウンへ (ネイティブスヌーズ)。
    func countdown(uuid: UUID) throws { try manager.countdown(id: uuid) }

    /// アラーム状態変化のストリーム (鳴動検知用)。
    var updates: AsyncStream<[Alarm]> {
        AsyncStream { cont in
            let task = Task {
                for await list in manager.alarmUpdates {
                    cont.yield(list)
                }
            }
            cont.onTermination = { _ in task.cancel() }
        }
    }

    func schedule(_ inst: AlarmInstanceDTO, sound: String?) async throws -> String? {
        if manager.authorizationState != .authorized {
            _ = try? await manager.requestAuthorization()
        }
        // 未認可で nil を返すと呼び出し側が「予約なしの .pending 記録」を残し
        // 二度と鳴らないゾンビ化するため throw で失敗を明示する
        guard manager.authorizationState == .authorized else {
            throw SchedulerError.notAuthorized
        }
        let uuid = Self.uuid(from: inst.id)
        if existingIds.contains(uuid) { try? manager.cancel(id: uuid) }

        _ = try await manager.schedule(id: uuid, configuration: Self.configuration(for: inst, sound: sound))
        return uuid.uuidString
    }

    /// 予約に渡す設定。停止ボタンの intent を付け忘れると #61 が戻るので、テストで中身を確かめられるよう分けている
    static func configuration(for inst: AlarmInstanceDTO, sound: String? = nil) -> AlarmManager.AlarmConfiguration<AppAlarmMetadata> {
        let date = Date(timeIntervalSince1970: TimeInterval(inst.triggerAtMillis) / 1000)
        let meta = AppAlarmMetadata(instanceId: inst.id, title: inst.title, snoozeMinutes: inst.snoozeMinutes)
        let alert: AlarmPresentation.Alert
        if #available(iOS 26.1, *) {
            alert = AlarmPresentation.Alert(
                title: LocalizedStringResource(stringLiteral: inst.title),
                secondaryButton: AlarmButton(
                    text: "スヌーズ",
                    textColor: .white,
                    systemImageName: "zzz"
                ),
                // .countdown = スヌーズを AlarmKit の postAlert カウントダウンに委譲
                secondaryButtonBehavior: .countdown
            )
        } else {
            // iOS 26.0 では stopButton 必須のシグネチャのみ提供 (26.1 で廃止)
            alert = AlarmPresentation.Alert(
                title: LocalizedStringResource(stringLiteral: inst.title),
                stopButton: AlarmButton(
                    text: "停止",
                    textColor: .white,
                    systemImageName: "stop.fill"
                ),
                secondaryButton: AlarmButton(
                    text: "スヌーズ",
                    textColor: .white,
                    systemImageName: "zzz"
                ),
                secondaryButtonBehavior: .countdown
            )
        }
        let presentation = AlarmPresentation(alert: alert)
        let attrs = AlarmAttributes(
            presentation: presentation,
            metadata: meta,
            tintColor: .indigo
        )
        let countdown = Alarm.CountdownDuration(
            preAlert: nil,
            postAlert: TimeInterval(inst.snoozeMinutes) * 60
        )
        // AlarmKit で指定できるのは音だけ (音量・だんだん大きく・バイブは iPhone の設定に従う)
        let alertSound: AlertConfiguration.AlertSound = sound.map { .named($0) } ?? .default
        return AlarmManager.AlarmConfiguration<AppAlarmMetadata>(
            countdownDuration: countdown,
            schedule: .fixed(date),
            attributes: attrs,
            // 停止ボタンで「止めた」を書き残す。アプリが見ていない間に止めた行を、予約が消えた行と見分けるため (#61)
            stopIntent: AlarmStopIntent(instanceId: inst.id),
            sound: alertSound
        )
    }

    func cancel(instanceId: String, reservationId: String?) async {
        try? manager.cancel(id: Self.uuid(from: instanceId))
    }

    func hasReservation(instanceId: String) async -> Bool {
        existingIds.contains(Self.uuid(from: instanceId))
    }

    /// インスタンス id 文字列から決定論的 UUID (AlarmKit は UUID 必須、差分適用のため決定的に)。
    static func uuid(from instanceId: String) -> UUID { ReservationUUID.from(instanceId) }
}

// MARK: - UNUserNotificationCenter フォールバック (iOS < 26)

final class NotificationScheduler: AlarmScheduler {

    /// UNUserNotificationCenter の上限 64 に対し余裕を残す
    static let maxCapacity = 55

    private let center = UNUserNotificationCenter.current()
    /// 予約と取消の宛先。テストでは偽物に替えて、予約した id を返す所を実行する
    private let port: NotificationCenterPort

    init(port: NotificationCenterPort = SystemNotificationCenter()) {
        self.port = port
    }

    var usesRealAlarms: Bool { false }

    var authorizationGranted: Bool {
        UserDefaults.standard.bool(forKey: "notifAuthorized")
    }

    var authorizationUndetermined: Bool {
        UserDefaults.standard.object(forKey: "notifAuthorized") == nil
    }

    func requestAuthorization() async {
        let ok = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        UserDefaults.standard.set(ok, forKey: "notifAuthorized")
    }

    func schedule(_ inst: AlarmInstanceDTO, sound: String?) async throws -> String? {
        let content = UNMutableNotificationContent()
        content.title = inst.title
        content.sound = sound.map { UNNotificationSound(named: UNNotificationSoundName($0)) } ?? .default
        content.interruptionLevel = .timeSensitive
        let date = Date(timeIntervalSince1970: TimeInterval(inst.triggerAtMillis) / 1000)
        let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
        try await port.submit(UNNotificationRequest(identifier: inst.id, content: content, trigger: trigger))
        // 予約できた印として返す。届いたかを後から推定する時に、予約に失敗した行と見分ける
        return inst.id
    }

    func cancel(instanceId: String, reservationId: String?) async {
        port.removePending([instanceId])
    }

    /// 通知の経路は AlarmKit の一覧を持たないので、消えた予約の判定 (#61) はしない
    var liveReservationIdsIfReadable: Set<UUID>? { nil }

    func hasReservation(instanceId: String) async -> Bool {
        await port.pendingRequestIds().contains(instanceId)
    }
}
