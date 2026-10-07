import WidgetKit
import SwiftUI

/// 「次のアラーム」ウィジェット。App Group 経由でアプリが書いた next_alarm.json を読む。
struct NextAlarmEntry: TimelineEntry {
    let date: Date
    let title: String
    let triggerAtMillis: Int64
    var hasAlarm: Bool { triggerAtMillis > 0 }
}

struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> NextAlarmEntry {
        NextAlarmEntry(date: .now, title: "アラーム", triggerAtMillis: Int64(Date().timeIntervalSince1970 * 1000))
    }
    func getSnapshot(in context: Context, completion: @escaping (NextAlarmEntry) -> Void) {
        completion(load())
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<NextAlarmEntry>) -> Void) {
        let entry = load()
        let next = entry.hasAlarm
            ? Date(timeIntervalSince1970: TimeInterval(entry.triggerAtMillis) / 1000).addingTimeInterval(60)
            : Date().addingTimeInterval(3600)
        completion(Timeline(entries: [entry], policy: .after(next)))
    }

    private func load() -> NextAlarmEntry {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.calendaralarm.app"),
              let data = try? Data(contentsOf: container.appendingPathComponent("next_alarm.json")),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let num = obj["triggerAtMillis"] as? NSNumber,
              let title = obj["title"] as? String else {
            return NextAlarmEntry(date: .now, title: "次のアラームなし", triggerAtMillis: 0)
        }
        return NextAlarmEntry(date: .now, title: title, triggerAtMillis: num.int64Value)
    }
}

struct WidgetView: View {
    let entry: NextAlarmEntry
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "bell.fill").font(.system(size: 12))
                    .foregroundStyle(Color(red: 0.42, green: 0.36, blue: 0.90))
                Text("次のアラーム").font(.system(size: 11, weight: .medium)).foregroundStyle(.secondary)
            }
            if entry.hasAlarm {
                Text(Date(timeIntervalSince1970: TimeInterval(entry.triggerAtMillis) / 1000), format: .dateTime.month().day().hour().minute())
                    .font(.system(size: 16, weight: .light))
                Text(entry.title).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
            } else {
                Text("なし").font(.system(size: 14)).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .containerBackground(.fill.tertiary, for: .widget)
    }
}

@main
struct CalendarAlarmWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CalendarAlarmWidget", provider: Provider()) { entry in
            WidgetView(entry: entry)
        }
        .configurationDisplayName("次のアラーム")
        .description("次に鳴るアラームを表示")
        .supportedFamilies([.systemSmall])
    }
}
