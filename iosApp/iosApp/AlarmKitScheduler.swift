import Foundation
import SwiftUI
import CryptoKit
import UserNotifications
import AlarmKit

/// 鳴動予約の抽象。実体は AlarmKit (iOS26+) または UNUserNotificationCenter。
protocol AlarmScheduler {
    /// true = AlarmKit の本物アラーム経路が使える
    var usesRealAlarms: Bool { get }
    var authorizationGranted: Bool { get }
    func requestAuthorization() async
    /// @returns AlarmKit UUID など予約側の識別子 (通知経路は nil)
    func schedule(_ inst: AlarmInstanceDTO) async throws -> String?
    func cancel(instanceId: String, reservationId: String?) async
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

    /// 鳴動中アラームを停止。
    func stop(uuid: UUID) throws { try manager.stop(id: uuid) }

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

    func schedule(_ inst: AlarmInstanceDTO) async throws -> String? {
        if manager.authorizationState != .authorized {
            _ = try? await manager.requestAuthorization()
        }
        guard manager.authorizationState == .authorized else { return nil }
        let uuid = Self.uuid(from: inst.id)
        if existingIds.contains(uuid) { try? manager.cancel(id: uuid) }

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
        let config = AlarmManager.AlarmConfiguration<AppAlarmMetadata>(
            countdownDuration: countdown,
            schedule: .fixed(date),
            attributes: attrs
        )
        _ = try await manager.schedule(id: uuid, configuration: config)
        return uuid.uuidString
    }

    func cancel(instanceId: String, reservationId: String?) async {
        try? manager.cancel(id: Self.uuid(from: instanceId))
    }

    /// インスタンス id 文字列から決定論的 UUID (AlarmKit は UUID 必須、差分適用のため決定的に)。
    static func uuid(from instanceId: String) -> UUID {
        var bytes = [UInt8](Insecure.MD5.hash(data: Data(instanceId.utf8)))
        bytes[6] = (bytes[6] & 0x0F) | 0x40
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        let t = uuid_t(bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                       bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15])
        return UUID(uuid: t)
    }
}

// MARK: - UNUserNotificationCenter フォールバック (iOS < 26)

final class NotificationScheduler: AlarmScheduler {

    /// UNUserNotificationCenter の上限 64 に対し余裕を残す
    static let maxCapacity = 55

    private let center = UNUserNotificationCenter.current()

    var usesRealAlarms: Bool { false }

    var authorizationGranted: Bool {
        UserDefaults.standard.bool(forKey: "notifAuthorized")
    }

    func requestAuthorization() async {
        let ok = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        UserDefaults.standard.set(ok, forKey: "notifAuthorized")
    }

    func schedule(_ inst: AlarmInstanceDTO) async throws -> String? {
        let content = UNMutableNotificationContent()
        content.title = inst.title
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        let date = Date(timeIntervalSince1970: TimeInterval(inst.triggerAtMillis) / 1000)
        let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
        try await center.add(UNNotificationRequest(identifier: inst.id, content: content, trigger: trigger))
        return nil
    }

    func cancel(instanceId: String, reservationId: String?) async {
        center.removePendingNotificationRequests(withIdentifiers: [instanceId])
    }
}
