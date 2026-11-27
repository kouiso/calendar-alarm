package com.calendaralarm.shared

import com.calendaralarm.shared.bridge.SharedBridge
import com.calendaralarm.shared.logic.DiagnosticReport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticReportTest {
    // 2026-11-16 08:00:05 JST
    private val generatedAt = 1794783605000L
    // 2026-11-16 07:05:09 JST
    private val at0705 = 1794780309000L
    // 2026-11-16 08:00:00 JST
    private val at0800 = 1794783600000L
    // 2026-11-16 08:30:00 JST
    private val at0830 = 1794785400000L
    // 2026-11-17 06:00:00 JST
    private val nextAt = 1794862800000L

    private fun input(
        platform: String = "android",
        log: List<DiagnosticReport.LogEntry> = emptyList(),
        knownTitles: List<String> = emptyList(),
        checks: List<DiagnosticReport.Check> = emptyList(),
    ) = DiagnosticReport.Input(
        platform = platform,
        appVersion = "0.1.0 (12)",
        osVersion = "Android 14 (API 34)",
        deviceModel = "Google Pixel 8",
        locale = "ja-JP",
        zoneId = "Asia/Tokyo",
        generatedAtMillis = generatedAt,
        muteAll = false,
        ringStatus = "READY",
        nextAlarmAtMillis = nextAt,
        checks = checks,
        log = log,
        knownTitles = knownTitles,
    )

    @Test
    fun `欄の順と形が決まっている`() {
        val text = DiagnosticReport.build(
            input(
                log = listOf(
                    DiagnosticReport.LogEntry(
                        atMillis = at0705, action = "DISMISS", detail = "ok",
                        instanceId = "sa:3:x", triggerAtMillis = at0800,
                    ),
                    DiagnosticReport.LogEntry(atMillis = at0830, action = "RESYNC", detail = "app start"),
                ),
                checks = listOf(
                    DiagnosticReport.Check("calendar", "OK"),
                    DiagnosticReport.Check("notification", "NG"),
                ),
            ),
        )
        val expected = listOf(
            "calendar-alarm diagnostic report v1",
            "generated: 2026-11-16 08:00:05",
            "platform: android",
            "app: 0.1.0 (12)",
            "os: Android 14 (API 34)",
            "device: Google Pixel 8",
            "locale: ja-JP",
            "titles: hidden",
            "mute_all: false",
            "ring_status: READY",
            "next_alarm: 2026-11-17 06:00:00",
            "checks:",
            "- calendar: OK",
            "- notification: NG",
            "log: 2 entries (newest first)",
            "2026-11-16 08:30:00 RESYNC | app start",
            "2026-11-16 07:05:09 DISMISS id=sa:3:x trigger=2026-11-16 08:00:00 | ok",
        ).joinToString("\n")
        assertEquals(expected, text)
    }

    @Test
    fun `次のアラームが無ければ none と書く`() {
        val text = DiagnosticReport.build(input().copy(nextAlarmAtMillis = null))
        assertTrue(text.contains("next_alarm: none"))
        assertTrue(text.contains("log: 0 entries (newest first)"))
    }

    @Test
    fun `題は必ず伏せられ title 欄と本文の題が置き換わる`() {
        val text = DiagnosticReport.build(
            input(
                log = listOf(
                    DiagnosticReport.LogEntry(
                        atMillis = at0705, action = "SNOOZE", detail = "歯医者 @11/16 08:00", title = "歯医者",
                    ),
                ),
                knownTitles = listOf("歯", "歯医者"),
            ),
        )
        assertTrue(text.contains("titles: hidden"))
        assertTrue(text.contains("title=[title] | [title] @11/16 08:00"))
        // 長い題を先に置き換えるので「歯」だけが残らない
        assertFalse(text.contains("歯"))
    }

    @Test
    fun `題が分かっている時はどの欄にも出ない`() {
        val titles = listOf("歯医者", "朝活")
        val text = DiagnosticReport.build(
            input(
                log = listOf(
                    DiagnosticReport.LogEntry(
                        atMillis = at0705, action = "ALARM_OFF",
                        detail = "単発アラーム消費で停止: 朝活 (id=3)", title = "朝活",
                    ),
                    DiagnosticReport.LogEntry(
                        atMillis = at0800, action = "MISS", detail = "歯医者 を鳴らせず", title = "歯医者",
                        instanceId = "ev:1", triggerAtMillis = at0800,
                    ),
                    // knownTitles に無くても、記録自身の title は detail からも伏せる
                    DiagnosticReport.LogEntry(atMillis = at0830, action = "ERROR", detail = "夜勤 failed", title = "夜勤"),
                ),
                knownTitles = titles,
            ),
        )
        for (t in titles + "夜勤") assertFalse(text.contains(t), "題 $t が漏れている")
        assertTrue(text.contains("| [title] (id=3)"))
    }

    @Test
    fun `記録は新しい順に300件まで`() {
        val log = (0..300).map {
            DiagnosticReport.LogEntry(atMillis = at0705 + it * 1000L, action = "SCHEDULE", detail = "n$it")
        }
        val text = DiagnosticReport.build(input(log = log))
        assertTrue(text.contains("log: 300 entries (newest first)"))
        val lines = text.lines()
        val first = lines.indexOfFirst { it.startsWith("log:") } + 1
        // 一番新しい n300 が先頭に来て、一番古い n0 が最後の行から落ちる
        assertTrue(lines[first].endsWith("| n300"))
        assertTrue(lines.last().endsWith("| n1"))
        assertFalse(lines.any { it.endsWith("| n0") })
    }

    @Test
    fun `detail の改行は空白にする`() {
        val text = DiagnosticReport.build(
            input(log = listOf(DiagnosticReport.LogEntry(atMillis = at0705, action = "ERROR", detail = "a\nb"))),
        )
        assertTrue(text.endsWith("| a b"))
    }

    @Test
    fun `題を本文に書く種類は後ろの書式だけ残す`() {
        fun detailOf(platform: String, action: String, detail: String): String {
            val text = DiagnosticReport.build(
                input(platform = platform, log = listOf(DiagnosticReport.LogEntry(at0705, action, detail))),
            )
            return text.lines().last().substringAfter("| ")
        }
        assertEquals("[title] @11/16 08:00", detailOf("ios", "MISSED", "消えた予定 @11/16 08:00"))
        assertEquals("[title] @11/16 08:00 via=lock", detailOf("ios", "DISMISS_OFFAPP", "歯医者 @11/16 08:00 via=lock"))
        assertEquals("[title] (alerting)", detailOf("ios", "FIRED", "朝会 (alerting)"))
        assertEquals("[title] (id=3)", detailOf("android", "ALARM_OFF", "単発アラーム消費で停止: 朝活 (id=3)"))
        assertEquals("[title] (通知)", detailOf("ios", "NOTIFY", "会議 (通知)"))
        // 後ろの書式に合わない時は [title] だけ
        assertEquals("[title]", detailOf("ios", "NOTIFY", "題だけ"))
    }

    @Test
    fun `表に無い種類は本文を変えない`() {
        val text = DiagnosticReport.build(
            input(log = listOf(DiagnosticReport.LogEntry(at0705, "DISMISS", "sa:3:2026-11-16"))),
        )
        assertTrue(text.endsWith("| sa:3:2026-11-16"))
    }

    @Test
    fun `空白だけの題では置き換えない`() {
        val text = DiagnosticReport.build(
            input(
                log = listOf(DiagnosticReport.LogEntry(at0705, "ERROR", "a b")),
                knownTitles = listOf(" ", ""),
            ),
        )
        assertTrue(text.endsWith("| a b"))
    }

    @Test
    fun `ブリッジは壊れた JSON なら空の文字列を返す`() {
        assertEquals("", SharedBridge.diagnosticReport("{"))
    }

    @Test
    fun `ブリッジは JSON から同じ文字を返す`() {
        val i = input(log = listOf(DiagnosticReport.LogEntry(at0705, "DISMISS", "ok")), knownTitles = listOf("歯医者"))
        val json = Json.encodeToString(i)
        assertEquals(DiagnosticReport.build(i), SharedBridge.diagnosticReport(json))
    }
}
