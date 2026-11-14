package com.calendaralarm

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.engine.LARGE_STOP_TAG
import com.calendaralarm.engine.RingingOverlay
import com.calendaralarm.engine.RingingSlideSpec
import com.calendaralarm.engine.SNOOZE_BUTTON_TAG
import com.calendaralarm.engine.SNOOZE_CHOOSE_TAG
import com.calendaralarm.engine.TITLE_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 代わりの画面 (RingingOverlay) の大きいボタンモード (#154) を、盤 p154 の 2 列目に合わせる。
 * スヌーズも停止と同じ大きさ、「スヌーズ時間を選ぶ」は出さない、予定名は 22 (mdpi なので 1px = 1dp = 1sp)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-mdpi")
class RingingOverlayLargeLayoutTest {

    private val app: CalendarAlarmApp = ApplicationProvider.getApplicationContext()

    private fun build(large: Boolean): View =
        RingingOverlay.buildView(app, "tm:layout", "LayoutTitle", 10, listOf(5, 10, 15), largeButtons = large)

    @Test
    fun `大きいボタンではスヌーズが停止と同じ高さで、スヌーズ時間を選ぶが出ない`() {
        val root = build(large = true)
        val snooze = root.findViewWithTag<View>(SNOOZE_BUTTON_TAG)
        val stop = root.findViewWithTag<View>(LARGE_STOP_TAG)
        assertEquals(124, RingingSlideSpec.LARGE_BUTTON_HEIGHT_DP)
        assertEquals(124, snooze.layoutParams.height)
        assertEquals("スヌーズと停止は同じ高さ", snooze.layoutParams.height, stop.layoutParams.height)
        assertNull("スヌーズ時間を選ぶは出さない", root.findViewWithTag<View>(SNOOZE_CHOOSE_TAG))
        val chips = ArrayList<View>()
        root.findViewsWithText(chips, "+5分", View.FIND_VIEWS_WITH_TEXT)
        assertEquals("チップは出さない", 0, chips.size)
    }

    @Test
    fun `停止と下の文字の間は、大きいボタンで12、通常で10`() {
        fun footGap(large: Boolean): Int {
            val root = build(large) as android.view.ViewGroup
            return (root.getChildAt(root.childCount - 1).layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin
        }
        assertEquals(RingingSlideSpec.LARGE_BUTTON_GAP_DP, footGap(true))
        assertEquals(12, footGap(true))
        assertEquals(10, footGap(false))
    }

    @Test
    fun `大きいボタンの文字は24で、予定名は22になる`() {
        val root = build(large = true)
        assertEquals(24f, (root.findViewWithTag<View>(SNOOZE_BUTTON_TAG) as TextView).textSize, 0.01f)
        assertEquals(24f, (root.findViewWithTag<View>(LARGE_STOP_TAG) as TextView).textSize, 0.01f)
        assertEquals(22f, (root.findViewWithTag<View>(TITLE_TAG) as TextView).textSize, 0.01f)
    }

    @Test
    fun `通常のスヌーズは80のまま、スヌーズ時間を選ぶが出る`() {
        val root = build(large = false)
        assertEquals(RingingSlideSpec.SNOOZE_HEIGHT_DP, root.findViewWithTag<View>(SNOOZE_BUTTON_TAG).layoutParams.height)
        assertNotNull(root.findViewWithTag<View>(SNOOZE_CHOOSE_TAG))
        assertEquals(30f, (root.findViewWithTag<View>(TITLE_TAG) as TextView).textSize, 0.01f)
    }
}
