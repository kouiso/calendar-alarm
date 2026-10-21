package com.calendaralarm

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.engine.AlarmReceiver
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.engine.RingingOverlay
import com.calendaralarm.shared.logic.SlideToStopRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 代わりの画面 (RingingOverlay) の停止スライダーの操作契約。
 * Compose の鳴動画面とは別に View で組んでいるため、RingingSlideStopTest とは別に固定する。
 * 止めてよいのは「丸を掴んで閾値まで引いた時」と「TalkBack のダブルタップ」だけ。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-mdpi")
class RingingOverlaySlideStopTest {

    private lateinit var app: CalendarAlarmApp
    private lateinit var slider: View

    private companion object {
        const val SLIDER = "スライドして停止"
        const val ID = "tm:overlay"

        // RingingOverlay.buildSlideToStop と同じ寸法 (mdpi なので dp = px)
        const val THUMB = 56f
        const val PAD = 8f
    }

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        val root = RingingOverlay.buildView(app, ID, "OverlaySlideTest", 10, listOf(5, 10, 15))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(740, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 360, 740)
        val found = ArrayList<View>()
        root.findViewsWithText(found, SLIDER, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        assertEquals("停止スライダーは1つだけ", 1, found.size)
        slider = found.single()
        assertTrue("掴める幅より広いトラックが要る: width=${slider.width}", slider.width > grabLimit() + 40f)
    }

    private fun grabLimit() = THUMB + PAD * SlideToStopRule.GRAB_PAD_MULTIPLIER

    private fun travel() = slider.width - THUMB - PAD * 2

    private fun dismissBroadcasts(): List<Intent> {
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(app).broadcastIntents.filter { it.action == AlarmService.ACTION_DISMISS }
    }

    private fun touch(action: Int, x: Float) {
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(t, t, action, x, slider.height / 2f, 0)
        slider.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** [fromX] で触れて、丸を [progress] (トラックの移動量に対する割合) まで引いて離す。 */
    private fun drag(fromX: Float, progress: Float) {
        val toX = fromX + progress * travel()
        touch(MotionEvent.ACTION_DOWN, fromX)
        touch(MotionEvent.ACTION_MOVE, (fromX + toX) / 2f)
        touch(MotionEvent.ACTION_MOVE, toX)
        touch(MotionEvent.ACTION_UP, toX)
    }

    private val thumbCenter get() = PAD + THUMB / 2f

    @Test
    fun `TalkBack のダブルタップで AlarmReceiver に停止を送る`() {
        val info = slider.createAccessibilityNodeInfo()
        assertTrue("TalkBack から押せる項目になっていない", info.isClickable)

        assertTrue(slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))

        val sent = dismissBroadcasts()
        assertEquals(1, sent.size)
        assertEquals(AlarmReceiver::class.java.name, sent.single().component?.className)
        assertEquals(ID, sent.single().getStringExtra(AlarmService.EXTRA_INSTANCE_ID))
    }

    @Test
    fun `丸をタップしただけでは止まらない`() {
        touch(MotionEvent.ACTION_DOWN, thumbCenter)
        touch(MotionEvent.ACTION_UP, thumbCenter)
        assertEquals(0, dismissBroadcasts().size)
    }

    @Test
    fun `掴める幅の外から引き始めると止まらない`() {
        drag(fromX = grabLimit() + 20f, progress = 1f)
        assertEquals(0, dismissBroadcasts().size)
    }

    @Test
    fun `閾値の手前で離すと止まらない`() {
        drag(fromX = thumbCenter, progress = SlideToStopRule.STOP_THRESHOLD - 0.1f)
        assertEquals(0, dismissBroadcasts().size)
    }

    @Test
    fun `丸を閾値を超えて引くと停止を送る`() {
        drag(fromX = thumbCenter, progress = SlideToStopRule.STOP_THRESHOLD + 0.1f)
        val sent = dismissBroadcasts()
        assertEquals(1, sent.size)
        assertEquals(ID, sent.single().getStringExtra(AlarmService.EXTRA_INSTANCE_ID))
    }
}
