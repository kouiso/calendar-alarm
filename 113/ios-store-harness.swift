import Foundation

// PR113 B1 の iOS 側の再現: アカウント 15分前 → カレンダーのスイッチを切って入れ直す → アカウント 30分前
var failures = 0
func check(_ ok: Bool, _ label: String) { print((ok ? "PASS " : "FAIL ") + label); if !ok { failures += 1 } }

@MainActor func run() {
    let s = Store()
    s.mutate {
        $0 = Store.Persisted()
        $0.defaultMinutesBefore = 0
        $0.calendars = [
            CalendarSource(id: "1", name: "work-cal", accountName: "work", color: 0, accountId: "src-work"),
            CalendarSource(id: "2", name: "home-cal", accountName: "home", color: 0, accountId: "src-home"),
        ]
    }
    s.setAccountSetting("src-work", AccountSettingDTO(minutesBefore: 15))
    // 設定画面のスイッチと同じ操作: rule(for:) を取り、enabled だけ変えて保存する
    var r = s.rule(for: "1"); r.enabled = false; s.setCalendarRule("1", r)
    r = s.rule(for: "1"); r.enabled = true; s.setCalendarRule("1", r)
    s.setAccountSetting("src-work", AccountSettingDTO(minutesBefore: 30))
    check(s.effectiveCalendarRules()["1"]?.minutesBefore == 30, "toggled calendar follows account 30 (engine): \(String(describing: s.effectiveCalendarRules()["1"]?.minutesBefore))")
    check(s.rule(for: "1").minutesBefore == 30, "toggled calendar follows account 30 (screen): \(s.rule(for: "1").minutesBefore)")
    check(s.effectiveCalendarRules()["1"]?.enabled == true, "toggled calendar stays enabled")

    #if PR113_AFTER
    s.setCalendarMinutes("1", 5)
    check(s.effectiveCalendarRules()["1"]?.minutesBefore == 5, "chosen 5 wins over account 30")
    s.setCalendarMinutes("1", nil)
    check(s.effectiveCalendarRules()["1"]?.minutesBefore == 30, "back to default follows account 30")
    #endif

    s.setAccountSetting("src-home", AccountSettingDTO(ring: false))
    check(s.effectiveCalendarRules()["2"]?.enabled == false, "home account ring off disables home calendar")
    check(s.effectiveCalendarRules()["1"]?.enabled == true, "work calendar still rings")

    // 旧データ: キーが無ければ、アプリ既定 (10) と違う分数の行だけ選んだ値とみなす
    var legacy = Store.Persisted()
    legacy.defaultMinutesBefore = 10
    legacy.calendarRules = ["a": AlarmRuleDTO(minutesBefore: 10), "b": AlarmRuleDTO(minutesBefore: 5)]
    var json = try! JSONSerialization.jsonObject(with: JSONEncoder().encode(legacy)) as! [String: Any]
    json.removeValue(forKey: "calendarMinutesChosen")
    let decoded = try! JSONDecoder().decode(Store.Persisted.self, from: JSONSerialization.data(withJSONObject: json))
    #if PR113_AFTER
    check(decoded.calendarMinutesChosen == ["b"], "legacy rows: only non-default minutes count as chosen: \(decoded.calendarMinutesChosen)")
    #else
    _ = decoded
    #endif
}

MainActor.assumeIsolated { run() }
print(failures == 0 ? "ALL PASS" : "FAILURES: \(failures)")
exit(failures == 0 ? 0 : 1)
