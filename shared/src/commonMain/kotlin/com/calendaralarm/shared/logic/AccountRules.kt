package com.calendaralarm.shared.logic

import com.calendaralarm.shared.model.AccountSetting
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.CalendarSource

/**
 * アカウント単位の設定 (使う / 鳴らす / 何分前) を配下のカレンダーへ当てはめる (局長決定6, #74)。
 *
 * - 「使う」が OFF のアカウントは、このアプリでは使わない。配下の予定は一覧にも出さず、鳴らさない
 *   (端末からアカウントは消さない)
 * - 「鳴らす」が OFF のアカウントは一覧には出すが鳴らさない
 * - 「何分前」はアカウントの既定。カレンダーごとに設定した値があればそちらが勝つ
 */
object AccountRules {

    data class Applied(
        /** 展開に渡すカレンダー単位のルール (カレンダーで決めたもの + アカウントの既定) */
        val calendarRules: Map<String, AlarmRule>,
        /** 鳴らさないカレンダー */
        val disabledCalendarIds: Set<String>,
        /** このアプリで使わない (一覧にも出さない) カレンダー */
        val hiddenCalendarIds: Set<String>,
    )

    fun apply(
        calendars: List<CalendarSource>,
        accounts: Map<String, AccountSetting>,
        explicitRules: Map<String, AlarmRule>,
        defaultRule: AlarmRule,
    ): Applied {
        val rules = explicitRules.toMutableMap()
        val disabled = explicitRules.filterValues { !it.enabled }.keys.toMutableSet()
        val hidden = mutableSetOf<String>()
        for (cal in calendars) {
            val account = accounts[cal.accountName] ?: continue
            if (!account.use) hidden += cal.id
            if (!account.use || !account.ring) disabled += cal.id
            val minutes = account.minutesBefore
            if (minutes != null && cal.id !in explicitRules) {
                rules[cal.id] = defaultRule.copy(minutesBefore = minutes)
            }
        }
        return Applied(rules, disabled, hidden)
    }

    /** 設定画面でアカウントごとにまとめる時の並び。アカウント名の順、同じアカウントは主カレンダーを先に */
    fun groupByAccount(calendars: List<CalendarSource>): List<Pair<String, List<CalendarSource>>> =
        calendars.groupBy { it.accountName }
            .toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it.lowercase() })
            .map { (account, cals) -> account to cals.sortedWith(compareByDescending<CalendarSource> { it.isPrimary }.thenBy { it.name }) }
}
