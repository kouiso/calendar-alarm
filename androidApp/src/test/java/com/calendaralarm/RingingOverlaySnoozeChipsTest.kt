package com.calendaralarm

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.engine.RingingOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 代わりの画面 (RingingOverlay) のスヌーズ操作の契約。
 * 既定の分数のボタンと、開閉式のプリセットチップ (別の分数) が、鳴動画面と同じ約束で AlarmService に届くこと。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-mdpi")
class RingingOverlaySnoozeChipsTest {

    private val app: CalendarAlarmApp = ApplicationProvider.getApplicationContext()

    private companion object {
        const val ID = "tm:overlay-snooze"
        const val TOGGLE_CLOSED = "スヌーズ時間を選ぶ ▾"
        const val TOGGLE_OPEN = "スヌーズ時間を選ぶ ▴"
    }

    private fun build(presets: List<Int>): View {
        val root = RingingOverlay.buildView(app, ID, "OverlaySnoozeTest", 10, presets)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(740, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 360, 740)
        return root
    }

    /** 閉じていて見えない View も含めて、文字が [text] と一致する TextView を木をたどって集める (findViewsWithText は見えない子を飛ばす)。 */
    private fun texts(root: View, text: String): List<View> {
        val found = ArrayList<View>()
        fun walk(v: View) {
            if (v is TextView && v.text.toString() == text) found.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return found
    }

    private fun only(root: View, text: String): View {
        val found = texts(root, text)
        assertEquals("「$text」は 1 つだけ", 1, found.size)
        return found.single()
    }

    private fun lastSnoozeIntent(): Intent? {
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(app).nextStartedService
    }

    @Test
    fun `既定のスヌーズは分数を付けずにサービスへ送る`() {
        val root = build(listOf(5, 15))

        only(root, "スヌーズ 10分").performClick()

        val sent = lastSnoozeIntent()
        assertNotNull("スヌーズが AlarmService に届いていない", sent)
        assertEquals(AlarmService::class.java.name, sent!!.component?.className)
        assertEquals(AlarmService.ACTION_SNOOZE, sent.action)
        assertEquals(ID, sent.getStringExtra(AlarmService.EXTRA_INSTANCE_ID))
        assertFalse("既定のスヌーズに分数を付けると、設定の分数が効かない", sent.hasExtra(AlarmService.EXTRA_SNOOZE_MINUTES))
    }

    @Test
    fun `チップは見出しを押すまで見えず、押すと開き、もう一度押すと閉じる`() {
        val root = build(listOf(5, 15))
        val chip = only(root, "+15分")
        val chipRowScroll = generateSequence(chip.parent as? View) { it.parent as? View }.first { it.visibility == View.GONE }
        assertEquals("最初は閉じている", View.GONE, chipRowScroll.visibility)

        only(root, TOGGLE_CLOSED).performClick()
        assertEquals(View.VISIBLE, chipRowScroll.visibility)
        assertEquals("開いたら見出しが開いた形になる", 1, texts(root, TOGGLE_OPEN).size)
        assertTrue("開いたら閉じた形の見出しは残らない", texts(root, TOGGLE_CLOSED).isEmpty())

        only(root, TOGGLE_OPEN).performClick()
        assertEquals(View.GONE, chipRowScroll.visibility)
        assertEquals(1, texts(root, TOGGLE_CLOSED).size)
    }

    @Test
    fun `チップを押すとその分数でスヌーズを送る`() {
        val root = build(listOf(5, 15, 30))
        only(root, TOGGLE_CLOSED).performClick()

        only(root, "+30分").performClick()

        val sent = lastSnoozeIntent()
        assertNotNull("チップのスヌーズが AlarmService に届いていない", sent)
        assertEquals(AlarmService.ACTION_SNOOZE, sent!!.action)
        assertEquals(ID, sent.getStringExtra(AlarmService.EXTRA_INSTANCE_ID))
        assertEquals(30, sent.getIntExtra(AlarmService.EXTRA_SNOOZE_MINUTES, -1))
    }

    @Test
    fun `プリセットが7つ以上でも出すチップは6つまで`() {
        val root = build(listOf(1, 5, 10, 15, 20, 30, 45, 60))

        val chips = listOf(1, 5, 10, 15, 20, 30, 45, 60).count { texts(root, "+${it}分").isNotEmpty() }

        assertEquals("1 行に詰まって分数が読めなくなるので 6 つで止める", 6, chips)
        assertTrue("7 つ目以降は出さない", texts(root, "+45分").isEmpty() && texts(root, "+60分").isEmpty())
    }

    @Test
    fun `プリセットが空なら見出しもチップも出さない`() {
        val root = build(emptyList())

        assertTrue(texts(root, TOGGLE_CLOSED).isEmpty())
        assertNull("チップが無いのに +N分 がある", (1..60).firstOrNull { texts(root, "+${it}分").isNotEmpty() })
        assertEquals("既定のスヌーズボタンは残る", 1, texts(root, "スヌーズ 10分").size)
    }
}
