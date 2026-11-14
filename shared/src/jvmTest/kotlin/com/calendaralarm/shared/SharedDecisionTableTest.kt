package com.calendaralarm.shared

import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.logic.RingStatus
import com.calendaralarm.shared.logic.SettingsPreview
import com.calendaralarm.shared.model.AlarmRule
import com.calendaralarm.shared.model.Availability
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.EventOverride
import com.calendaralarm.shared.model.EventTypeFilter
import com.calendaralarm.shared.model.InviteFilter
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * リポジトリ直下 testdata/shared-decisions.json の「入力 -> 期待値」の表を読み、shared の判定と照合する (#301)。
 * 同じ表を iosApp/unit-test からも読めるよう、置き場は commonTest の resources ではなく直下の testdata/ にした。
 * 1行でも食い違えば、その行の name を付けて落ちる。
 */
class SharedDecisionTableTest {

    private val root: JsonObject = Json.parseToJsonElement(locateTable().readText()).jsonObject

    /** Gradle のテストの作業ディレクトリは shared/。そこから親へ辿って表を探す (起動場所に依らない)。 */
    private fun locateTable(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "testdata/shared-decisions.json")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        error("testdata/shared-decisions.json が見つからない (user.dir=${System.getProperty("user.dir")})")
    }

    private fun rows(key: String): List<JsonObject> {
        val rows = root[key]!!.jsonArray.map { it.jsonObject }
        check(rows.isNotEmpty()) { "$key の表が空" }
        return rows
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun expandEvents() {
        for (row in rows("expandEvents")) {
            val name = row.str("name")
            val ev = row["event"]!!.jsonObject
            val start = Instant.parse(ev.str("start")).toEpochMilliseconds()
            val event = CalendarEvent(
                id = "e", calendarId = "c1", title = "会議",
                startMillis = start, endMillis = start + 3_600_000, allDay = ev["allDay"]!!.jsonPrimitive.boolean,
            )
            val ruleJson = row["rule"]!!.jsonObject
            val rule = AlarmRule(
                minutesBefore = ruleJson["minutesBefore"]?.jsonPrimitive?.int ?: 0,
                allDayMinutes = ruleJson["allDayMinutes"]?.jsonPrimitive?.int ?: 540,
                extraOffsets = ruleJson["extraOffsets"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(),
                startAction = ruleJson["startAction"]?.jsonPrimitive?.content?.let(EventAction::valueOf) ?: EventAction.ALARM,
            )
            val overrides = row["override"]?.jsonObject?.let { o ->
                mapOf(
                    event.instanceKey to EventOverride(
                        action = o["action"]?.jsonPrimitive?.content?.let(EventAction::valueOf),
                        minutesBefore = o["minutesBefore"]?.jsonPrimitive?.intOrNull,
                    ),
                )
            } ?: emptyMap()
            val actual = AlarmExpander.expandEvents(
                events = listOf(event),
                calendarRules = mapOf("c1" to rule),
                overrides = overrides,
                disabledCalendarIds = emptySet(),
                now = Instant.parse(row.str("now")),
                horizon = Instant.parse(row.str("horizon")),
                zone = TimeZone.of("Asia/Tokyo"),
            ).map { Triple(it.triggerAtMillis, it.minutesBefore, it.delivery.name) }
            val expected = row["expected"]!!.jsonArray.map {
                val o = it.jsonObject
                Triple(Instant.parse(o.str("trigger")).toEpochMilliseconds(), o["minutesBefore"]!!.jsonPrimitive.int, o.str("delivery"))
            }
            assertEquals(expected, actual, "expandEvents: $name")
        }
    }

    @Test
    fun ringStatus() {
        for (row in rows("ringStatus")) {
            val name = row.str("name")
            val i = row["in"]!!.jsonObject
            val s = RingStatus.summarize(
                missingPermissions = i["missingPermissions"]!!.jsonPrimitive.int,
                recentMissed = i["recentMissed"]!!.jsonPrimitive.int,
                hasNextAlarm = i["hasNextAlarm"]!!.jsonPrimitive.boolean,
                alarmMuted = i["alarmMuted"]!!.jsonPrimitive.boolean,
            )
            val e = row["expected"]!!.jsonObject
            assertEquals(e.str("kind"), s.kind.name, "ringStatus kind: $name")
            assertEquals(e["missingPermissions"]!!.jsonPrimitive.int, s.missingPermissions, "ringStatus missingPermissions: $name")
            assertEquals(e["recentMissed"]!!.jsonPrimitive.int, s.recentMissed, "ringStatus recentMissed: $name")
            assertEquals(e["showPermissionBanner"]!!.jsonPrimitive.boolean, s.showPermissionBanner, "ringStatus showPermissionBanner: $name")
            assertEquals(e["showMissedBanner"]!!.jsonPrimitive.boolean, s.showMissedBanner, "ringStatus showMissedBanner: $name")
        }
    }

    @Test
    fun eventTypeFilter() {
        for (row in rows("eventTypeFilter")) {
            val name = row.str("name")
            val f = row["filter"]?.jsonObject
            fun flag(k: String, default: Boolean) = f?.get(k)?.jsonPrimitive?.booleanOrNull ?: default
            val filter = EventTypeFilter(
                birthday = flag("birthday", false),
                absence = flag("absence", false),
                workplace = flag("workplace", false),
                focusTime = flag("focusTime", false),
                task = flag("task", true),
                event = flag("event", true),
                free = flag("free", true),
            )
            val ev = row["event"]!!.jsonObject
            // 種類は必ず Google の eventType で渡す。タイトルでの分類 (#306 で変更中) には依存させない
            val event = CalendarEvent(
                id = "e", calendarId = "c1", title = "会議",
                startMillis = 1_800_000_000_000L, endMillis = 1_800_003_600_000L, allDay = false,
                googleEventType = ev.str("googleEventType"),
                availability = Availability.valueOf(ev.str("availability")),
            )
            val override = row["override"]?.jsonObject?.let {
                EventOverride(action = it["action"]?.jsonPrimitive?.content?.let(EventAction::valueOf))
            }
            val actual = AlarmExpander.passesEventFilters(event, override, InviteFilter(), filter)
            assertEquals(row["expected"]!!.jsonPrimitive.boolean, actual, "eventTypeFilter: $name")
        }
    }

    @Test
    fun clockMath() {
        for (row in rows("snoozeClock")) {
            val actual = SettingsPreview.snoozeClock(row["alarmMinuteOfDay"]!!.jsonPrimitive.int, row["snoozeMinutes"]!!.jsonPrimitive.int)
            assertEquals(row.str("expected"), actual, "snoozeClock: ${row.str("name")}")
        }
        for (row in rows("ringClock")) {
            val actual = SettingsPreview.ringClock(row["eventMinuteOfDay"]!!.jsonPrimitive.int, row["minutesBefore"]!!.jsonPrimitive.int)
            assertEquals(row.str("expected"), actual, "ringClock: ${row.str("name")}")
        }
    }
}
