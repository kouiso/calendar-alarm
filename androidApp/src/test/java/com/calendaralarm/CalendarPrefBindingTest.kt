package com.calendaralarm

import com.calendaralarm.data.calendar.CalendarIdentity
import com.calendaralarm.data.calendar.CalendarPrefBinding
import com.calendaralarm.data.calendar.CalendarPrefBinding.SEP
import com.calendaralarm.data.db.CalendarPrefEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * カレンダーごとの設定を、端末の行番号ではなく端末をまたいで変わらない鍵に結び付ける (#268)。
 * Android に依存しない純粋な関数なので Robolectric なしで確かめる。
 */
class CalendarPrefBindingTest {

    private fun cal(
        id: String,
        syncId: String? = null,
        name: String? = null,
        displayName: String? = null,
        accountName: String = "a@x",
    ) = CalendarIdentity(id, "com.google", accountName, syncId, name, displayName)

    private fun row(key: String, calendarId: String, enabled: Boolean = true, accountName: String = "a@x") =
        CalendarPrefEntity(
            calendarId = calendarId, enabled = enabled, minutesBefore = null,
            calendarKey = key, accountType = "com.google", accountName = accountName,
        )

    private fun key(kind: String, value: String, accountName: String = "a@x") =
        "com.google$SEP$accountName$SEP$kind:$value"

    @Test
    fun `行番号が変わっても同じ同期用の ID のカレンダーに設定が付く`() {
        val result = CalendarPrefBinding.bind(
            listOf(row(key("S", "work"), calendarId = "", enabled = false)),
            listOf(cal("9", syncId = "work")),
        )
        assertFalse(result.byCalendarId.getValue("9").enabled)
        assertEquals(1, result.rewrites.size)
        assertEquals("9", result.rewrites[0].row.calendarId)
    }

    @Test
    fun `行番号が入れ替わった 2 つのカレンダーの設定が入れ替わらない`() {
        val rows = listOf(
            row(key("S", "work"), calendarId = "5", enabled = false),
            row(key("S", "family"), calendarId = "6", enabled = true),
        )
        val result = CalendarPrefBinding.bind(
            rows,
            listOf(cal("5", syncId = "family"), cal("6", syncId = "work")),
        )
        assertFalse(result.byCalendarId.getValue("6").enabled)
        assertTrue(result.byCalendarId.getValue("5").enabled)
    }

    @Test
    fun `同期用の ID が無ければ内部名、無ければ表示名で鍵を作る`() {
        assertTrue(CalendarPrefBinding.keyOf(cal("1", syncId = "s", name = "n", displayName = "d")).endsWith("${SEP}S:s"))
        assertTrue(CalendarPrefBinding.keyOf(cal("1", syncId = "", name = "n", displayName = "d")).endsWith("${SEP}N:n"))
        assertTrue(CalendarPrefBinding.keyOf(cal("1", syncId = null, name = null, displayName = "d")).endsWith("${SEP}D:d"))
    }

    @Test
    fun `旧形式の行は同じ行番号のカレンダーに付き、鍵が書き直される`() {
        val legacy = CalendarPrefEntity("1", enabled = false, minutesBefore = null)
        val c = cal("1", syncId = "work")
        val result = CalendarPrefBinding.bind(listOf(legacy), listOf(c))
        assertFalse(result.byCalendarId.getValue("1").enabled)
        assertEquals(CalendarPrefBinding.keyOf(c), result.rewrites[0].row.calendarKey)
        assertEquals("id:1", result.rewrites[0].stored.calendarKey)
    }

    @Test
    fun `表示名を変えただけなら同じ行番号の設定を引き継ぐ`() {
        val result = CalendarPrefBinding.bind(
            listOf(row(key("D", "仕事"), calendarId = "3", enabled = false)),
            listOf(cal("3", displayName = "業務")),
        )
        assertFalse(result.byCalendarId.getValue("3").enabled)
        assertEquals(key("D", "業務"), result.rewrites[0].row.calendarKey)
    }

    @Test
    fun `同期用の ID が違うカレンダーが同じ行番号を使い回しても引き継がない`() {
        val result = CalendarPrefBinding.bind(
            listOf(row(key("S", "work"), calendarId = "5", enabled = false)),
            listOf(cal("5", syncId = "family")),
        )
        assertNull(result.byCalendarId["5"])
        assertTrue(result.rewrites.isEmpty())
    }

    @Test
    fun `カレンダーの一覧に無い行番号の行は、その行番号で効く`() {
        val result = CalendarPrefBinding.bind(
            listOf(CalendarPrefEntity("1", enabled = false, minutesBefore = null)),
            emptyList(),
        )
        assertNotNull(result.byCalendarId["1"])
        assertTrue(result.rewrites.isEmpty())
    }

    @Test
    fun `同じ鍵のカレンダーが 2 つあれば両方に付き、書き直すのは 1 件`() {
        val result = CalendarPrefBinding.bind(
            listOf(row(key("D", "予定"), calendarId = "", enabled = false)),
            listOf(cal("4", displayName = "予定"), cal("2", displayName = "予定")),
        )
        assertFalse(result.byCalendarId.getValue("2").enabled)
        assertFalse(result.byCalendarId.getValue("4").enabled)
        assertEquals(1, result.rewrites.size)
        assertEquals("2", result.rewrites[0].row.calendarId)
    }

    @Test
    fun `新しい鍵が同じになる旧形式の行が 2 つあれば書き直さず、それぞれの設定が残る`() {
        val rows = listOf(
            CalendarPrefEntity("2", enabled = true, minutesBefore = null),
            CalendarPrefEntity("4", enabled = false, minutesBefore = null),
        )
        val calendars = listOf(cal("2", displayName = "予定"), cal("4", displayName = "予定"))
        val result = CalendarPrefBinding.bind(rows, calendars)
        assertTrue(result.byCalendarId.getValue("2").enabled)
        assertFalse(result.byCalendarId.getValue("4").enabled)
        assertTrue(result.rewrites.isEmpty())
        // 書き直さないので、次の再同期でも同じ結果になる
        val again = CalendarPrefBinding.bind(rows, calendars)
        assertFalse(again.byCalendarId.getValue("4").enabled)
    }

    @Test
    fun `同期用の ID の設定は、内部名だけのカレンダーが同じ行番号を使い回しても引き継がない`() {
        val result = CalendarPrefBinding.bind(
            listOf(row(key("S", "work"), calendarId = "5", enabled = false)),
            listOf(cal("5", name = "family")),
        )
        assertNull(result.byCalendarId["5"])
        assertTrue(result.rewrites.isEmpty())
    }

    @Test
    fun `同期用の ID・内部名の設定は、表示名だけのカレンダーへ引き継がない`() {
        for (kind in listOf("S", "N")) {
            val result = CalendarPrefBinding.bind(
                listOf(row(key(kind, "work"), calendarId = "5", enabled = false)),
                listOf(cal("5", displayName = "family")),
            )
            assertNull("$kind から D へは引き継がない", result.byCalendarId["5"])
            assertTrue(result.rewrites.isEmpty())
        }
    }

    @Test
    fun `表示名や内部名の鍵の行は、後から同期用の ID が付いたカレンダーへ引き継ぐ`() {
        val fromName = CalendarPrefBinding.bind(
            listOf(row(key("N", "work"), calendarId = "5", enabled = false)),
            listOf(cal("5", syncId = "work", name = "work")),
        )
        assertFalse(fromName.byCalendarId.getValue("5").enabled)
        val fromDisplay = CalendarPrefBinding.bind(
            listOf(row(key("D", "仕事"), calendarId = "5", enabled = false)),
            listOf(cal("5", name = "work", displayName = "仕事")),
        )
        assertFalse(fromDisplay.byCalendarId.getValue("5").enabled)
    }

    @Test
    fun `旧形式の行が 1 件だけでも、新しい鍵を共有するカレンダーがあれば書き直さない`() {
        val rows = listOf(CalendarPrefEntity("2", enabled = false, minutesBefore = null))
        val calendars = listOf(cal("2", displayName = "予定"), cal("4", displayName = "予定"))
        val result = CalendarPrefBinding.bind(rows, calendars)
        assertFalse(result.byCalendarId.getValue("2").enabled)
        assertNull("行の無い 4 に設定が移らない", result.byCalendarId["4"])
        assertTrue(result.rewrites.isEmpty())
        // 次の結び付けでも 4 は鳴らす側のまま
        assertNull(CalendarPrefBinding.bind(rows, calendars).byCalendarId["4"])
    }

    @Test
    fun `saveKey は鍵が重ならなければ鍵へ移し、重なれば今の鍵のまま保存する`() {
        val unique = listOf(cal("1", syncId = "a"), cal("2", syncId = "b"))
        assertEquals(CalendarPrefBinding.keyOf(unique[0]), CalendarPrefBinding.saveKey("1", unique, null))

        val dup = listOf(cal("2", displayName = "予定"), cal("4", displayName = "予定"))
        assertEquals("id:2", CalendarPrefBinding.saveKey("2", dup, null))
        val legacy = CalendarPrefEntity("2", enabled = true, minutesBefore = null)
        assertEquals("id:2", CalendarPrefBinding.saveKey("2", dup, legacy))
        // 読めなかった時は今の鍵のまま
        assertEquals("id:2", CalendarPrefBinding.saveKey("2", null, legacy))
    }
}
