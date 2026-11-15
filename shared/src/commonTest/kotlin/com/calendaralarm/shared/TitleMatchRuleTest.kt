package com.calendaralarm.shared

import com.calendaralarm.shared.bridge.SharedBridge
import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventType
import com.calendaralarm.shared.model.TitleMatchRule
import com.calendaralarm.shared.model.classifyEventType
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #306 と #201 の判断B「iOS は #307 まで別のまま」: 題から種類を推定する規則を、
 * Android は WHOLE_TITLE_FOR_TIMED、iOS が通る SharedBridge.expand は LEGACY_SUBSTRING (#306 より前と同じ) にする。
 */
class TitleMatchRuleTest {

    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val now = LocalDateTime(2026, 11, 5, 12, 0).toInstant(tokyo)
    private val horizon = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 14 * 86_400_000L)

    private class Row(val title: String, val allDay: Boolean, val whole: EventType, val legacy: EventType)

    @Test
    fun `題の規則ごとの分類の表_時刻付きは題全体の一致と今までの部分一致で結果が分かれる`() {
        val rows = listOf(
            Row("出社して面談", false, EventType.EVENT, EventType.WORKPLACE),
            Row("有休申請の締切", false, EventType.EVENT, EventType.ABSENCE),
            Row("Microsoft Office 研修", false, EventType.EVENT, EventType.WORKPLACE),
            Row("Takeaway 発注締切", false, EventType.EVENT, EventType.ABSENCE),
            Row("タスク整理ミーティング", false, EventType.EVENT, EventType.TASK),
            Row("田中さんの誕生日", false, EventType.EVENT, EventType.BIRTHDAY),
            Row("田中さんの誕生日", true, EventType.BIRTHDAY, EventType.BIRTHDAY),
            Row("不在", false, EventType.ABSENCE, EventType.ABSENCE),
            // オフィス・自宅・在宅は #306 で足した語なので、今までの規則では普通の予定のまま
            Row("オフィス", false, EventType.WORKPLACE, EventType.EVENT),
            Row("自宅", false, EventType.WORKPLACE, EventType.EVENT),
            Row("在宅", true, EventType.WORKPLACE, EventType.EVENT),
            // 正規化は題全体の一致の側だけ。今までの規則は小文字化だけで部分一致を見る
            Row("ＡＢＳＥＮＴ", false, EventType.ABSENCE, EventType.EVENT),
            Row("Out of office", false, EventType.ABSENCE, EventType.WORKPLACE),
            // ooo は語に無い。空の題はどの語とも一致しない
            Row("OOO", false, EventType.EVENT, EventType.EVENT),
            Row("", false, EventType.EVENT, EventType.EVENT),
            Row("   ", false, EventType.EVENT, EventType.EVENT),
        )
        for (r in rows) {
            val label = "「${r.title}」 allDay=${r.allDay}"
            assertEquals(r.whole, classifyEventType(r.title, r.allDay, TitleMatchRule.WHOLE_TITLE_FOR_TIMED), "$label 題全体")
            assertEquals(r.whole, classifyEventType(r.title, r.allDay), "$label 既定は題全体")
            assertEquals(r.legacy, classifyEventType(r.title, r.allDay, TitleMatchRule.LEGACY_SUBSTRING), "$label 今までの部分一致")
        }
    }

    private fun timed(id: String, title: String): CalendarEvent {
        val start = LocalDateTime(2026, 11, 6, 10, 0).toInstant(tokyo).toEpochMilliseconds()
        return CalendarEvent(
            id = id, calendarId = "c1", title = title, startMillis = start, endMillis = start + 3_600_000L,
            allDay = false,
        )
    }

    private fun allDay(id: String, title: String): CalendarEvent {
        val start = LocalDateTime(2026, 11, 6, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
        return CalendarEvent(
            id = id, calendarId = "c1", title = title, startMillis = start, endMillis = start + 86_400_000L,
            allDay = true,
        )
    }

    private val events = listOf(
        timed("mendan", "出社して面談"),
        timed("takeaway", "Takeaway 発注締切"),
        timed("office", "オフィス"),
        allDay("zaitaku", "在宅"),
        timed("teirei", "定例"),
    )

    private fun keysOf(vararg ids: String): Set<String> =
        events.filter { it.id in ids }.map { it.instanceKey }.toSet()

    @Test
    fun `iOSが通るSharedBridgeのexpandは今までの部分一致で予約する_判断Bで307まで変えない`() {
        // 既定の絞り込み (勤務場所・不在は鳴らさない)。#306 より前 (master) と同じ結果になること:
        // 部分一致で勤務場所・不在になる「出社して面談」「Takeaway 発注締切」は予約されず、
        // #306 で足した語だけの「オフィス」「在宅」は普通の予定として予約される
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val req = SharedBridge.ExpandRequest(
            events = events,
            nowMillis = now.toEpochMilliseconds(),
            horizonMillis = horizon.toEpochMilliseconds(),
            zoneId = tokyo.id,
        )
        val res = json.decodeFromString(
            SharedBridge.ExpandResult.serializer(),
            SharedBridge.expand(json.encodeToString(SharedBridge.ExpandRequest.serializer(), req)),
        )
        assertEquals(keysOf("office", "zaitaku", "teirei"), res.instances.mapNotNull { it.eventId }.toSet())
    }

    @Test
    fun `Androidが通るexpandEventsの既定は時刻付きを題全体の一致で予約する`() {
        val out = AlarmExpander.expandEvents(events, emptyMap(), emptyMap(), emptySet(), now, horizon, zone = tokyo)
        assertEquals(keysOf("mendan", "takeaway", "teirei"), out.mapNotNull { it.eventId }.toSet())
    }
}
