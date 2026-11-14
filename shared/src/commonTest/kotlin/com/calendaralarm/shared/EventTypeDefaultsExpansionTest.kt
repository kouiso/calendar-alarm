package com.calendaralarm.shared

import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.EventOverride
import com.calendaralarm.shared.model.EventType
import com.calendaralarm.shared.model.EventTypeFilter
import com.calendaralarm.shared.model.resolveEventType
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #201: 勤務場所・誕生日・不在の予定が、既定の絞り込みで終日・繰り返し・日付の境目でも予約されないこと、
 * 設定を ON にした時に今の通知時刻のままであること、個別指定の優先順を固定する。
 * 「現在の挙動を固定」の試験は挙動を変えない。題での判定は #306 で時刻のある予定だけ題全体の一致に変わり、その試験は名前を変えて残している。
 */
class EventTypeDefaultsExpansionTest {

    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val now = LocalDateTime(2026, 11, 5, 12, 0).toInstant(tokyo)
    private val horizon = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 14 * 86_400_000L)

    /** Google の種類で3種類を表す (title は種類と無関係な題にして、種類の判定が Google 側に由来することを確かめる) */
    private val types = listOf("workingLocation", "birthday", "outOfOffice")

    private fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime(y, mo, d, h, mi).toInstant(tokyo).toEpochMilliseconds()

    private fun utcMidnight(y: Int, mo: Int, d: Int): Long =
        LocalDateTime(y, mo, d, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()

    private fun timed(id: String, type: String?, startMillis: Long, recurring: Boolean = false) = CalendarEvent(
        id = id, calendarId = "c1", title = "定例", startMillis = startMillis,
        endMillis = startMillis + 3_600_000L, allDay = false, googleEventType = type, recurring = recurring,
    )

    private fun allDay(id: String, type: String?, y: Int, mo: Int, d: Int, reminders: List<Int> = emptyList()) =
        CalendarEvent(
            id = id, calendarId = "c1", title = "定例", startMillis = utcMidnight(y, mo, d),
            endMillis = utcMidnight(y, mo, d) + 86_400_000L, allDay = true, googleEventType = type,
            calendarReminderMinutes = reminders,
        )

    private fun expand(
        events: List<CalendarEvent>,
        filter: EventTypeFilter = EventTypeFilter(),
        overrides: Map<String, EventOverride> = emptyMap(),
        rule: AlarmRule = AlarmRule(),
        reminders: Boolean = false,
    ) = AlarmExpander.expandEvents(
        events, mapOf("c1" to rule), overrides, emptySet(), now, horizon, zone = tokyo,
        eventTypeFilter = filter, importEventReminders = reminders,
    )

    @Test
    fun `既定では3種類の終日の予定は予約されず通常の終日の予定は9時に予約される(現在の挙動を固定)`() {
        val events = types.mapIndexed { i, t -> allDay("t$i", t, 2026, 11, 6) } + allDay("normal", "default", 2026, 11, 6)
        val out = expand(events)
        assertEquals(1, out.size)
        assertTrue(out.single().eventId!!.startsWith("c1:normal:"))
        assertEquals(local(2026, 11, 6, 9, 0), out.single().triggerAtMillis)
    }

    @Test
    fun `既定では3種類の終日の予定にカレンダー側のリマインダーを取り込んでも予約されない(現在の挙動を固定)`() {
        val events = types.mapIndexed { i, t -> allDay("t$i", t, 2026, 11, 6, reminders = listOf(0, 60, 540)) }
        assertEquals(emptyList(), expand(events, reminders = true))
    }

    @Test
    fun `種類をONにした終日の予定は今までの時刻(既定は当日9時)で予約される(現在の挙動を固定)`() {
        val on = EventTypeFilter(birthday = true, absence = true, workplace = true)
        val out = expand(types.mapIndexed { i, t -> allDay("t$i", t, 2026, 11, 6) }, filter = on)
        assertEquals(3, out.size)
        assertTrue(out.all { it.triggerAtMillis == local(2026, 11, 6, 9, 0) })
    }

    @Test
    fun `既定では3種類の繰り返しの予定は毎回とも予約されない(現在の挙動を固定)`() {
        val events = types.flatMapIndexed { i, t ->
            (6..9).map { d -> timed("t$i-$d", t, local(2026, 11, d, 0, 0), recurring = true) }
        } + (6..9).map { d -> timed("normal-$d", "default", local(2026, 11, d, 0, 0), recurring = true) }
        val out = expand(events)
        assertEquals(4, out.size)
        assertTrue(out.all { it.eventId!!.startsWith("c1:normal-") })
    }

    @Test
    fun `既定では0時ちょうど前後の3種類の予定は予約されず通常の予定は0時に予約される(現在の挙動を固定)`() {
        val boundaries = listOf(local(2026, 11, 6, 0, 0), local(2026, 11, 5, 23, 59), local(2026, 11, 6, 0, 1))
        val events = types.flatMapIndexed { i, t -> boundaries.mapIndexed { j, s -> timed("t$i-$j", t, s) } } +
            timed("normal", null, local(2026, 11, 6, 0, 0))
        val out = expand(events)
        assertEquals(1, out.size)
        assertEquals(local(2026, 11, 6, 0, 0), out.single().triggerAtMillis)
    }

    @Test
    fun `題が語そのものの3種類も既定では予約されない(現在の挙動を固定)`() {
        val titles = listOf("勤務場所", "誕生日", "不在")
        val events = titles.mapIndexed { i, t ->
            CalendarEvent(
                id = "t$i", calendarId = "c1", title = t, startMillis = local(2026, 11, 6, 0, 0),
                endMillis = local(2026, 11, 6, 1, 0), allDay = false,
            )
        }
        assertEquals(emptyList(), expand(events))
    }

    @Test
    fun `個別にアラームを選んだ3種類の予定は既定の絞り込みでも予約される(現在の挙動を固定)`() {
        val events = types.mapIndexed { i, t -> timed("t$i", t, local(2026, 11, 6, 10, 0)) }
        val ov = events.associate { it.instanceKey to EventOverride(action = EventAction.ALARM) }
        assertEquals(3, expand(events, overrides = ov).size)
    }

    @Test
    fun `個別に鳴らさないを選んだ予定は種類がONでも予約されない(現在の挙動を固定)`() {
        val on = EventTypeFilter(birthday = true, absence = true, workplace = true)
        val events = types.mapIndexed { i, t -> timed("t$i", t, local(2026, 11, 6, 10, 0)) }
        val ov = events.associate { it.instanceKey to EventOverride(action = EventAction.MUTE) }
        assertEquals(emptyList(), expand(events, filter = on, overrides = ov))
    }

    @Test
    fun `終日を鳴らさないカレンダーは個別のアラーム指定より強い(現在の挙動を固定)`() {
        val e = allDay("t", "outOfOffice", 2026, 11, 6)
        val ov = mapOf(e.instanceKey to EventOverride(action = EventAction.ALARM))
        assertEquals(emptyList(), expand(listOf(e), overrides = ov, rule = AlarmRule(allDayMinutes = -1)))
    }

    @Test
    fun `現在の挙動を固定_終日の予定に取り込んだ540分前のリマインダーは当日0時に鳴る`() {
        // #382: 終日の予定のカレンダー側リマインダーは「その日の0時から何分前」だが、今は9時の基準から引くので
        // 540分前は当日の0時になる。#382 で直すとこの期待値は変わる (この試験が反転する箇所)
        val e = allDay("a", "default", 2026, 11, 6, reminders = listOf(540))
        val triggers = expand(listOf(e), reminders = true).map { it.triggerAtMillis }.toSet()
        assertEquals(setOf(local(2026, 11, 6, 0, 0), local(2026, 11, 6, 9, 0)), triggers)
    }

    private fun titled(id: String, title: String, allDay: Boolean) = CalendarEvent(
        id = id, calendarId = "c1", title = title,
        startMillis = if (allDay) utcMidnight(2026, 11, 6) else local(2026, 11, 6, 10, 0),
        endMillis = if (allDay) utcMidnight(2026, 11, 7) else local(2026, 11, 6, 11, 0), allDay = allDay,
    )

    private fun typeOf(title: String, allDay: Boolean) = resolveEventType(titled("x", title, allDay))

    @Test
    fun `時刻のある予定は題全体の一致だけで判定する_語を含む普通の予定は分類されず鳴る`() {
        // #306 (#201 の判断B): 2026-11-11 の issue コメントの例。普通の予定なので鳴る
        val titles = listOf(
            "出社して面談", "有休申請の締切", "Microsoft Office 研修", "昼休み明けの定例", "Takeaway 発注締切",
            "オフィス周辺ランチ",
        )
        val events = titles.mapIndexed { i, t -> titled("t$i", t, allDay = false) }
        assertEquals(titles.map { EventType.EVENT }, events.map { resolveEventType(it) })
        assertEquals(titles.size, expand(events).size)
    }

    @Test
    fun `時刻のある予定は題全体の一致だけで判定する_題が語そのものなら分類されて予約されない`() {
        val cases = mapOf(
            "不在" to EventType.ABSENCE, "在宅" to EventType.WORKPLACE, "出社" to EventType.WORKPLACE,
            "オフィス" to EventType.WORKPLACE, "自宅" to EventType.WORKPLACE, "誕生日" to EventType.BIRTHDAY,
        )
        cases.forEach { (title, type) -> assertEquals(type, typeOf(title, allDay = false), title) }
        assertEquals(emptyList(), expand(cases.keys.mapIndexed { i, t -> titled("t$i", t, allDay = false) }))
    }

    @Test
    fun `時刻のある予定は題全体の一致だけで判定する_大文字小文字と空白と全角の違いは同じ語として扱う`() {
        assertEquals(EventType.ABSENCE, typeOf("  OUT OF OFFICE ", allDay = false))
        assertEquals(EventType.ABSENCE, typeOf("Out\u3000of  Office", allDay = false))
        assertEquals(EventType.ABSENCE, typeOf("ＡＢＳＥＮＴ", allDay = false))
        assertEquals(EventType.WORKPLACE, typeOf("Ｗｏｒｋ　Ｌｏｃａｔｉｏｎ", allDay = false))
        assertEquals(EventType.WORKPLACE, typeOf(" 在宅 ", allDay = false))
        assertEquals(EventType.FOCUS_TIME, typeOf("Focus Time", allDay = false))
    }

    @Test
    fun `時刻のある予定は題全体の一致だけで判定する_改行なし空白U+00A0も普通の空白と同じに畳む`() {
        // JVM の Regex の \s は U+00A0 を空白とみなさない。端 (trim) と中で定義が食い違わないことを固定する
        assertEquals(EventType.ABSENCE, typeOf("Out\u00A0\u00A0of office", allDay = false))
        assertEquals(EventType.ABSENCE, typeOf("Out \u00A0of\u2003 office", allDay = false))
        assertEquals(EventType.ABSENCE, typeOf("\u00A0Out of office\u00A0", allDay = false))
        assertEquals(EventType.EVENT, typeOf("Out\u00A0of\u00A0office review", allDay = false))
    }

    @Test
    fun `終日の予定は今までどおり題に語を含めば分類される`() {
        assertEquals(EventType.BIRTHDAY, typeOf("○○さんの誕生日", allDay = true))
        assertEquals(EventType.ABSENCE, typeOf("夏季休暇", allDay = true))
        assertEquals(EventType.WORKPLACE, typeOf("勤務場所: オフィス", allDay = true))
        assertEquals(EventType.WORKPLACE, typeOf("Microsoft Office 研修", allDay = true))
    }

    @Test
    fun `オフィスと自宅と在宅は題全体の一致だけで勤務場所になり終日でも部分一致しない`() {
        for (allDay in listOf(false, true)) {
            assertEquals(EventType.EVENT, typeOf("オフィス周辺ランチ", allDay), "オフィス周辺ランチ allDay=$allDay")
            assertEquals(EventType.EVENT, typeOf("自宅へ配送", allDay), "自宅へ配送 allDay=$allDay")
            assertEquals(EventType.EVENT, typeOf("在宅ワーク相談", allDay), "在宅ワーク相談 allDay=$allDay")
            assertEquals(EventType.WORKPLACE, typeOf("オフィス", allDay), "オフィス allDay=$allDay")
            assertEquals(EventType.WORKPLACE, typeOf("自宅", allDay), "自宅 allDay=$allDay")
            assertEquals(EventType.WORKPLACE, typeOf("在宅", allDay), "在宅 allDay=$allDay")
        }
    }

    @Test
    fun `GoogleのeventTypeが取れていれば時刻のある予定でも題の推定より優先する`() {
        val e = titled("g", "不在", allDay = false).copy(googleEventType = "default")
        assertEquals(EventType.EVENT, resolveEventType(e))
        assertEquals(1, expand(listOf(e)).size)
    }

    @Test
    fun `時刻のある予定は5種類とも題全体の一致だけで判定する_サイレントモードとタスクを含む題は分類されない`() {
        assertEquals(EventType.EVENT, typeOf("サイレントモード明けの定例", allDay = false))
        assertEquals(EventType.EVENT, typeOf("Focus time review", allDay = false))
        assertEquals(EventType.EVENT, typeOf("タスク整理ミーティング", allDay = false))
        assertEquals(EventType.FOCUS_TIME, typeOf("サイレントモード", allDay = false))
        assertEquals(EventType.TASK, typeOf("タスク", allDay = false))
        assertEquals(EventType.FOCUS_TIME, typeOf("サイレントモード明けの定例", allDay = true))
    }
}
