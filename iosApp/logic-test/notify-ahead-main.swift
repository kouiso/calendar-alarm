import Foundation

// #51 PR2: 通知で届く予定がこの先にあるか (`notificationDeliveryAhead`) の判定。Models.swift と一緒に macOS でコンパイルして走らせる
import Foundation
var fails = 0
func check(_ name: String, _ got: Bool, _ want: Bool) { if got != want { fails += 1; print("FAIL", name, got, want) } else { print("PASS", name) } }
func iso(_ s: String) -> Int64 { let f = ISO8601DateFormatter(); return Int64(f.date(from: s)!.timeIntervalSince1970 * 1000) }
let rule = AlarmRuleDTO()
let n = EventAction.notify.rawValue, a = EventAction.alarm.rawValue
func ev(_ start: String, allDay: Bool) -> CalendarEventDTO {
    CalendarEventDTO(id: "e", calendarId: "c", title: "t", startMillis: iso(start), endMillis: iso(start) + 3_600_000, allDay: allDay)
}
// 終日 10/22 (UTC 0時)。ローカル9時 (LA は 16:00Z、JST は 00:00Z)
let allDay = ev("2026-10-22T00:00:00Z", allDay: true)
let tz = TimeZone.current.identifier
if tz == "America/Los_Angeles" {
    check("LA all-day 08:00 local, notify at 09:00 still ahead", notificationDeliveryAhead(allDay, rule, nil, actions: (n, nil), importReminders: false, nowMillis: iso("2026-10-22T15:00:00Z")), true)
    check("LA all-day 10:00 local, already passed", notificationDeliveryAhead(allDay, rule, nil, actions: (n, nil), importReminders: false, nowMillis: iso("2026-10-22T17:00:00Z")), false)
} else {
    check("JST all-day 08:00 local, ahead", notificationDeliveryAhead(allDay, rule, nil, actions: (n, nil), importReminders: false, nowMillis: iso("2026-10-21T23:00:00Z")), true)
    check("JST all-day 10:00 local, passed", notificationDeliveryAhead(allDay, rule, nil, actions: (n, nil), importReminders: false, nowMillis: iso("2026-10-22T01:00:00Z")), false)
}
// 時刻つき: 開始15分前の通知は、開始前でも15分前を過ぎていれば先ではない
var r15 = rule; r15.minutesBefore = 15
let timed = ev("2026-10-22T10:00:00Z", allDay: false)
check("timed start-15, now 09:50 -> trigger passed", notificationDeliveryAhead(timed, r15, nil, actions: (n, nil), importReminders: false, nowMillis: iso("2026-10-22T09:50:00Z")), false)
check("timed start-15, now 09:40 -> ahead", notificationDeliveryAhead(timed, r15, nil, actions: (n, nil), importReminders: false, nowMillis: iso("2026-10-22T09:40:00Z")), true)
// 開始はアラーム、リマインダー(追加30分前)だけ通知: 09:50 では30分前も過ぎている
var rx = rule; rx.extraOffsets = [30]
check("reminder-only notify passed", notificationDeliveryAhead(timed, rx, nil, actions: (a, n), importReminders: false, nowMillis: iso("2026-10-22T09:50:00Z")), false)
check("reminder-only notify ahead", notificationDeliveryAhead(timed, rx, nil, actions: (a, n), importReminders: false, nowMillis: iso("2026-10-22T09:20:00Z")), true)
check("alarm only never counts", notificationDeliveryAhead(timed, rx, nil, actions: (a, nil), importReminders: false, nowMillis: iso("2026-10-22T09:00:00Z")), false)
check("imported calendar reminder 60 counts", notificationDeliveryAhead(ev("2026-10-22T10:00:00Z", allDay: false).withReminders([60]), rule, nil, actions: (a, n), importReminders: true, nowMillis: iso("2026-10-22T08:30:00Z")), true)
extension CalendarEventDTO { func withReminders(_ m: [Int]) -> CalendarEventDTO { var c = self; c.calendarReminderMinutes = m; return c } }

// 開始(アラーム)と同じ分のリマインダー(通知)は重複で作られない。違う分なら通知が残る
var rdup = rule; rdup.minutesBefore = 10; rdup.extraOffsets = [10]
check("alarm start + notify reminder at same offset -> no notification", notificationDeliveryAhead(timed, rdup, nil, actions: (a, n), importReminders: false, nowMillis: iso("2026-10-22T09:00:00Z")), false)
var rdiff = rule; rdiff.minutesBefore = 10; rdiff.extraOffsets = [30]
check("alarm start + notify reminder at different offset -> notification", notificationDeliveryAhead(timed, rdiff, nil, actions: (a, n), importReminders: false, nowMillis: iso("2026-10-22T09:00:00Z")), true)
check("muted start leaves the same-offset reminder", notificationDeliveryAhead(timed, rdup, nil, actions: (EventAction.mute.rawValue, n), importReminders: false, nowMillis: iso("2026-10-22T09:00:00Z")), true)
check("global mute drops the alarm start, so the same-offset notify reminder still needs permission", notificationDeliveryAhead(timed, rdup, nil, actions: (a, n), importReminders: false, muteAll: true, nowMillis: iso("2026-10-22T09:00:00Z")), true)
// 端末の暦が和暦でも終日の時刻は変わらない
check("all-day with current calendar \(Calendar.current.identifier)", notificationDeliveryAhead(allDay, rule, nil, actions: (n, nil), importReminders: false, nowMillis: iso(tz == "America/Los_Angeles" ? "2026-10-22T15:00:00Z" : "2026-10-21T23:00:00Z")), true)
print("calendar:", Calendar.current.identifier)
print(fails == 0 ? "ALL PASS" : "FAILURES \(fails)")
exit(fails == 0 ? 0 : 1)
