package com.calendaralarm.shared

import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.logic.AlarmPlanner
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.EventOverride
import com.calendaralarm.shared.model.InviteFilter
import com.calendaralarm.shared.model.InviteStatus
import com.calendaralarm.shared.model.RepeatMode
import com.calendaralarm.shared.model.StandaloneAlarm
import com.calendaralarm.shared.model.TitleCodeSettings
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TZ = TimeZone.of("Asia/Tokyo")
private fun ldt(s: String) = LocalDateTime.parse(s).toInstant(TZ)

class AlarmExpanderTest {

    private val now: Instant = ldt("2026-10-05T08:00:00")
    private val horizon: Instant = ldt("2026-10-19T08:00:00")

    private fun event(
        id: String,
        calId: String = "c1",
        start: Instant,
        title: String = "会議",
    ) = CalendarEvent(
        id = id, calendarId = calId, title = title,
        startMillis = start.toEpochMilliseconds(),
        endMillis = start.toEpochMilliseconds() + 3_600_000,
        allDay = false,
    )

    @Test
    fun `イベントが開始時刻ちょうどに展開される`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            events = listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule()),
            overrides = emptyMap(),
            disabledCalendarIds = emptySet(),
            now = now, horizon = horizon,
        )
        assertEquals(1, out.size)
        assertEquals(ldt("2026-10-05T10:00:00").toEpochMilliseconds(), out[0].triggerAtMillis)
    }

    @Test
    fun `X分前ルールが適用される`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule(minutesBefore = 15)),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon,
        )
        assertEquals(ldt("2026-10-05T09:45:00").toEpochMilliseconds(), out[0].triggerAtMillis)
        assertEquals("ev:c1:e1:${ev.startMillis}:b15", out[0].id)
    }

    @Test
    fun `prefs行の無いカレンダーはグローバル既定ルールが効く`() {
        // UIは「15分前」と表示するので、エンジンも既定で15分前に鳴るべき
        // (calendar_prefs 行が無いカレンダーで開始時刻に鳴ってしまう退行防止)
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = emptyMap(), // pref 行なし
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon,
            defaultRule = AlarmRule(minutesBefore = 15),
        )
        assertEquals(ldt("2026-10-05T09:45:00").toEpochMilliseconds(), out[0].triggerAtMillis)
    }

    @Test
    fun `イベント上書きがカレンダールールに優先する`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule(minutesBefore = 15)),
            overrides = mapOf(ev.instanceKey to EventOverride(minutesBefore = 5)),
            disabledCalendarIds = emptySet(),
            now = now, horizon = horizon,
        )
        assertEquals(ldt("2026-10-05T09:55:00").toEpochMilliseconds(), out[0].triggerAtMillis)
    }

    @Test
    fun `ミュートと無効カレンダーと無効ルールが除外される`() {
        val ev1 = event("e1", calId = "mutedCal", start = ldt("2026-10-05T10:00:00"))
        val ev2 = event("e2", calId = "offCal", start = ldt("2026-10-05T10:00:00"))
        val ev3 = event("e3", calId = "ruleOff", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev1, ev2, ev3),
            calendarRules = mapOf(
                "mutedCal" to AlarmRule(),
                "offCal" to AlarmRule(),
                "ruleOff" to AlarmRule(enabled = false),
            ),
            overrides = mapOf(ev1.instanceKey to EventOverride(action = EventAction.MUTE)),
            disabledCalendarIds = setOf("offCal"),
            now = now, horizon = horizon,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `過去のイベントはグレース幅だけ残る`() {
        val recent = event("e1", start = ldt("2026-10-05T07:30:00")) // 30分前 → 残る
        val stale = event("e2", start = ldt("2026-10-05T05:00:00"))  // 3時間前 → 捨てる
        val out = AlarmExpander.expandEvents(
            listOf(recent, stale),
            calendarRules = mapOf("c1" to AlarmRule()),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon,
        )
        assertEquals(listOf("e1"), out.map { it.eventId?.split(":")?.get(1) })
    }

    private fun allDayEvent(id: String, utcStartMillis: Long, calId: String = "c1") = CalendarEvent(
        id = id, calendarId = calId, title = "終日予定",
        startMillis = utcStartMillis, endMillis = utcStartMillis + 86_400_000,
        allDay = true,
    )

    @Test
    fun `終日イベントは深夜ではなくカレンダーの終日時刻に鳴る`() {
        // Google 同期の終日イベントは startMillis=UTC 0:00 (=JST 9:00)。
        // ルール 8:00 指定なら JST 8:00 に鳴るべき。既定 9:00 なら 9:00。
        val utcStart = Instant.parse("2026-10-08T00:00:00Z").toEpochMilliseconds()
        val ev = allDayEvent("ad1", utcStart)
        val out8 = AlarmExpander.expandEvents(
            listOf(ev), mapOf("c1" to AlarmRule(allDayMinutes = 480)),
            emptyMap(), emptySet(), now, horizon, zone = TZ,
        )
        assertEquals(ldt("2026-10-08T08:00:00").toEpochMilliseconds(), out8.single().triggerAtMillis)
        val outDefault = AlarmExpander.expandEvents(
            listOf(ev), mapOf("c1" to AlarmRule()),
            emptyMap(), emptySet(), now, horizon, zone = TZ,
        )
        assertEquals(ldt("2026-10-08T09:00:00").toEpochMilliseconds(), outDefault.single().triggerAtMillis)
    }

    @Test
    fun `終日イベントの鳴動OFFは展開しない`() {
        val ev = allDayEvent("ad1", Instant.parse("2026-10-08T00:00:00Z").toEpochMilliseconds())
        val out = AlarmExpander.expandEvents(
            listOf(ev), mapOf("c1" to AlarmRule(allDayMinutes = -1)),
            emptyMap(), emptySet(), now, horizon, zone = TZ,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `追加リマインダーが個別インスタンスになる`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule(minutesBefore = 15, extraOffsets = listOf(30, 60))),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon, zone = TZ,
        )
        assertEquals(listOf(15, 30, 60), out.map { it.minutesBefore })
        assertEquals(3, out.size)
        assertEquals(ldt("2026-10-05T09:45:00").toEpochMilliseconds(), out[0].triggerAtMillis)
        assertEquals(ldt("2026-10-05T09:00:00").toEpochMilliseconds(), out[2].triggerAtMillis)
    }

    @Test
    fun `イベント個別の追加リマインダーがカレンダー既定を置き換える`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule(minutesBefore = 15, extraOffsets = listOf(30))),
            overrides = mapOf(ev.instanceKey to EventOverride(extraOffsets = listOf(60))),
            disabledCalendarIds = emptySet(), now = now, horizon = horizon, zone = TZ,
        )
        assertEquals(listOf(15, 60), out.map { it.minutesBefore })
    }

    @Test
    fun `曜日繰り返しと例外日`() {
        val alarm = StandaloneAlarm(
            id = 7, hour = 7, minute = 30,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            exceptions = setOf(LocalDate(2026, 10, 7)),
        )
        val out = AlarmExpander.expandStandalone(alarm, now = now, days = 8, zone = TZ)
        // 10-05(Mon) 7:30 は30分前=グレース幅60分内→残る。10-07(Wed)は例外。10-09(Fri), 10-12(Mon) も残る
        assertEquals(
            listOf(LocalDate(2026, 10, 5), LocalDate(2026, 10, 9), LocalDate(2026, 10, 12)),
            out.map {
                Instant.fromEpochMilliseconds(it.triggerAtMillis)
                    .toLocalDateTime(TZ).date
            },
        )
    }

    @Test
    fun `単発アラームは当日が過ぎたら翌日に繰り越す`() {
        val alarm = StandaloneAlarm(id = 1, hour = 6, minute = 0) // now=08:00 → 当日分はグレース超過
        val out = AlarmExpander.expandStandalone(alarm, now = now, days = 14, zone = TZ)
        // 10-05T06:00 は2時間前(グレース60分超)→翌10-06T06:00が最初の回
        assertEquals(1, out.size)
        assertEquals(LocalDate(2026, 10, 6), Instant.fromEpochMilliseconds(out[0].triggerAtMillis).toLocalDateTime(TZ).date)
    }

    @Test
    fun `タイムゾーン変更でローカル時刻が動いてもUTC絶対時刻が正しい`() {
        val ny = TimeZone.of("America/New_York")
        val alarm = StandaloneAlarm(id = 1, hour = 9, minute = 0, daysOfWeek = DayOfWeek.entries.toSet())
        val outTokyo = AlarmExpander.expandStandalone(alarm, now = now, days = 1, zone = TZ)
        val outNy = AlarmExpander.expandStandalone(alarm, now = now, days = 1, zone = ny)
        // 東京 9:00 = UTC 0:00、NY 9:00 = UTC 13:00(EDT)。同じ wall-clock でも絶対時刻は別
        assertTrue(outTokyo[0].triggerAtMillis < outNy[0].triggerAtMillis)
    }

    // ---- 条件ドメイン (元アプリのタイトルコード/招待フィルタ/3状態アクション) ----

    @Test
    fun `アクションが NOTIFY のインスタンスは delivery に残る`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule(startAction = EventAction.NOTIFY)),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon, zone = TZ,
        )
        assertEquals(EventAction.NOTIFY, out.single().delivery)
    }

    @Test
    fun `リマインダーだけ通知のみにできる`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf(
                "c1" to AlarmRule(
                    minutesBefore = 15, extraOffsets = listOf(30),
                    reminderAction = EventAction.NOTIFY,
                ),
            ),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon, zone = TZ,
        )
        // 開始15分前=アラーム、30分前=通知のみ
        assertEquals(
            listOf(EventAction.ALARM, EventAction.NOTIFY),
            out.sortedBy { it.minutesBefore }.map { it.delivery },
        )
    }

    @Test
    fun `タイトルコードの必ず鳴らすがルール MUTE を上書く`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"), title = "朝会 -w")
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule(startAction = EventAction.MUTE)),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon, zone = TZ,
            titleCodes = TitleCodeSettings(alwaysCodes = listOf("-w")),
        )
        assertEquals(1, out.size)
        assertEquals(EventAction.ALARM, out[0].delivery)
    }

    @Test
    fun `タイトルコードの鳴らさないが必ず鳴らすより優先される`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"), title = "朝会 -w -x")
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule()),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon, zone = TZ,
            titleCodes = TitleCodeSettings(
                alwaysCodes = listOf("-w"), neverCodes = listOf("-x"),
            ),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `コードの適用範囲が開始のみなら追加リマインダーは強制しない`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"), title = "朝会 -w")
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf(
                "c1" to AlarmRule(
                    minutesBefore = 15, extraOffsets = listOf(30),
                    startAction = EventAction.MUTE,
                    reminderAction = EventAction.MUTE,
                ),
            ),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon, zone = TZ,
            titleCodes = TitleCodeSettings(
                alwaysCodes = listOf("-w"),
                applyToStart = true, applyToReminders = false,
            ),
        )
        // 開始だけ強制ALARM。リマインダーはルール MUTE のまま
        assertEquals(1, out.size)
        assertEquals(15, out[0].minutesBefore)
    }

    @Test
    fun `招待予定フィルタが拒否ステータスを落とす`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
            .copy(inviteStatus = InviteStatus.DECLINED)
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule()),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon, zone = TZ,
            inviteFilter = InviteFilter(declined = false),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `明示的なアラーム指定は招待フィルタより優先する`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
            .copy(inviteStatus = InviteStatus.DECLINED)
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule()),
            overrides = mapOf(ev.instanceKey to EventOverride(action = EventAction.ALARM)),
            disabledCalendarIds = emptySet(),
            now = now, horizon = horizon, zone = TZ,
            inviteFilter = InviteFilter(declined = false),
        )
        assertEquals(1, out.size)
    }

    @Test
    fun `予定側リマインダーが取り込まれる`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
            .copy(calendarReminderMinutes = listOf(10, 60))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule(minutesBefore = 0)),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon, zone = TZ,
            importEventReminders = true,
        )
        assertEquals(listOf(0, 10, 60), out.map { it.minutesBefore })
    }

    @Test
    fun `予定側リマインダー取込OFFでは出ない`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
            .copy(calendarReminderMinutes = listOf(10))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule(minutesBefore = 0)),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon, zone = TZ,
            importEventReminders = false,
        )
        assertEquals(1, out.size)
    }

    @Test
    fun `同時刻の開始と追加リマインダーは重複しない`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf(
                "c1" to AlarmRule(minutesBefore = 15, extraOffsets = listOf(15, 30)),
            ),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon, zone = TZ,
        )
        assertEquals(listOf(15, 30), out.map { it.minutesBefore })
    }
}

class AlarmPlannerTest {
    private val now: Instant = ldt("2026-10-05T08:00:00")

    private fun inst(id: String, trigger: Instant) = com.calendaralarm.shared.model.AlarmInstance(
        id = id, triggerAtMillis = trigger.toEpochMilliseconds(),
        title = id, kind = com.calendaralarm.shared.model.AlarmKind.EVENT,
    )

    @Test
    fun `新規・変更・削除・即時鳴動が正しく分類される`() {
        val scheduled = mapOf(
            "same" to ldt("2026-10-05T09:00:00").toEpochMilliseconds(),
            "moved" to ldt("2026-10-05T09:00:00").toEpochMilliseconds(),
            "gone" to ldt("2026-10-05T09:00:00").toEpochMilliseconds(),
        )
        val desired = listOf(
            inst("same", ldt("2026-10-05T09:00:00")),      // 変化なし
            inst("moved", ldt("2026-10-05T10:00:00")),    // 時刻変更→再予約
            inst("new", ldt("2026-10-05T11:00:00")),      // 新規
            inst("overdue", ldt("2026-10-05T07:30:00")),  // 過去(グレース内)→即鳴動
        )
        val plan = AlarmPlanner.plan(scheduled, desired, now)
        assertEquals(setOf("moved", "new", "overdue"), plan.toSchedule.map { it.id }.toSet())
        assertEquals(listOf("gone"), plan.toCancel)
        assertEquals(listOf("overdue"), plan.toFireNow.map { it.id })
    }
}

class MuteAllTest {
    private val now: Instant = ldt("2026-10-05T08:00:00")
    private val horizon: Instant = ldt("2026-10-19T00:00:00")

    private fun event(id: String, start: Instant, title: String = "予定") = CalendarEvent(
        id = id, calendarId = "c1", title = title,
        startMillis = start.toEpochMilliseconds(),
        endMillis = start.toEpochMilliseconds() + 3_600_000,
        allDay = false,
    )

    @Test
    fun `一括ミュートはALARMだけ落とし NOTIFY は残る`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf(
                "c1" to AlarmRule(
                    minutesBefore = 15, extraOffsets = listOf(30),
                    reminderAction = EventAction.NOTIFY,
                ),
            ),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon, zone = TZ,
            muteAll = true,
        )
        // 開始15分前=ALARM→消える、30分前=NOTIFY→残る
        assertEquals(listOf(EventAction.NOTIFY), out.map { it.delivery })
    }

    @Test
    fun `一括ミュートでも開始時の NOTIFY は残る`() {
        val ev = event("e1", start = ldt("2026-10-05T10:00:00"))
        val out = AlarmExpander.expandEvents(
            listOf(ev),
            calendarRules = mapOf("c1" to AlarmRule(startAction = EventAction.NOTIFY)),
            overrides = emptyMap(), disabledCalendarIds = emptySet(),
            now = now, horizon = horizon, zone = TZ,
            muteAll = true,
        )
        assertEquals(1, out.size)
        assertEquals(EventAction.NOTIFY, out[0].delivery)
    }
}

class RepeatModeTest {
    private val now: Instant = ldt("2026-10-05T08:00:00")

    private fun alarm(
        mode: RepeatMode? = null,
        interval: Int = 1,
        anchor: String? = null,
        days: Set<DayOfWeek> = emptySet(),
    ) = StandaloneAlarm(
        id = 1, enabled = true, hour = 7, minute = 30,
        daysOfWeek = days,
        repeatMode = mode,
        repeatInterval = interval,
        repeatAnchorMillis = anchor?.let { LocalDate.parse(it).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() },
    )

    @Test
    fun `旧形式は曜日なし=ONCE 曜日あり=WEEKLY`() {
        assertEquals(RepeatMode.ONCE, alarm().effectiveRepeatMode())
        assertEquals(RepeatMode.WEEKLY, alarm(days = setOf(DayOfWeek.MONDAY)).effectiveRepeatMode())
    }

    @Test
    fun `毎月モードは窓外の次回も1件出す`() {
        // 10/5 現在、毎月20日 → 14日窓には10/20が1件入る
        val out = AlarmExpander.expandStandalone(
            alarm(mode = RepeatMode.MONTHLY, anchor = "2026-10-01"),
            now = now, days = 14, zone = TZ,
        )
        assertTrue(out.isNotEmpty())
        // anchor=1日だと日付は「1日」になる (起点日の日を使う)
        val out20 = AlarmExpander.expandStandalone(
            alarm(mode = RepeatMode.MONTHLY, anchor = "2026-10-20"),
            now = now, days = 14, zone = TZ,
        )
        assertEquals(1, out20.size)
        assertTrue(out20[0].id.endsWith(":2026-10-20"))
    }

    @Test
    fun `毎月モードで窓を超える次回も拾う`() {
        // 毎月25日、now=10/5 → 10/25は窓内。窓外だけのケース: 毎月1日で now=10/5 だと
        // 次回 11/1 は窓(10/19)外でも1件出る
        val out = AlarmExpander.expandStandalone(
            alarm(mode = RepeatMode.MONTHLY, anchor = "2026-09-01"),
            now = now, days = 14, zone = TZ,
        )
        assertEquals(1, out.size)
        assertTrue(out[0].id.endsWith(":2026-11-01"))
    }

    @Test
    fun `月末をまたぐ毎月は短い月で丸める`() {
        // 起点31日: 10月31日は窓外(10/19まで)なので次回=10/31が1件
        val out = AlarmExpander.expandStandalone(
            alarm(mode = RepeatMode.MONTHLY, anchor = "2026-08-31"),
            now = now, days = 14, zone = TZ,
        )
        assertEquals(1, out.size)
        assertTrue(out[0].id.endsWith(":2026-10-31"))
    }

    @Test
    fun `日ごとモードは過去の起点から次回を出す`() {
        // 起点9/1の7日ごと → 10/5以降の最初の発生日を出す
        val out = AlarmExpander.expandStandalone(
            alarm(mode = RepeatMode.INTERVAL_DAYS, interval = 7, anchor = "2026-09-01"),
            now = now, days = 14, zone = TZ,
        )
        assertTrue(out.isNotEmpty())
        assertTrue(out[0].triggerAtMillis > now.toEpochMilliseconds())
        // 連続発生は7日周期のはず
        if (out.size >= 2) {
            val gap = out[1].triggerAtMillis - out[0].triggerAtMillis
            assertEquals(7L * 24 * 3600 * 1000, gap)
        }
    }

    @Test
    fun `繰返しアラームの発生日は例外日を飛ばす`() {
        val out = AlarmExpander.expandStandalone(
            alarm(mode = RepeatMode.INTERVAL_DAYS, interval = 1, anchor = "2026-10-05").copy(
                exceptions = setOf(LocalDate(2026, 10, 6)),
            ),
            now = now, days = 7, zone = TZ,
        )
        assertTrue(out.none { it.id.endsWith(":2026-10-06") })
    }

    @Test
    fun `周期型はグレース幅内の過去発生も残す`() {
        // now=08:00、毎月5日 07:30 → 30分前の本日分がグレース(60分)内。
        // 旧実装では triggerAt>now で落として次回(11/5)に飛び、
        // resync で既存 pending 行が無言 CANCEL されて鳴らなかった
        val out = AlarmExpander.expandStandalone(
            alarm(mode = RepeatMode.MONTHLY, anchor = "2026-10-05"),
            now = now, days = 14, zone = TZ,
        )
        assertTrue(out.any { it.id.endsWith(":2026-10-05") })
    }

    @Test
    fun `曜日空のWEEKLYはONCEとして次の1回を出す`() {
        // 曜日未選択のまま毎週保存すると旧実装では永遠に鳴らなかった。
        // 「毎週鳴らす意図に一番近いのは次の1回」なので ONCE に倒す
        val out = AlarmExpander.expandStandalone(
            alarm(mode = RepeatMode.WEEKLY, days = emptySet()),
            now = now, days = 14, zone = TZ,
        )
        // hour=7:30 は now=08:00 の30分前 = グレース内 → 当日分が即時鳴動対象で出る
        assertEquals(1, out.size)
        assertTrue(out[0].id.endsWith(":2026-10-05"))
    }
}
