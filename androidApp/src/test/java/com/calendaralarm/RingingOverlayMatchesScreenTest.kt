package com.calendaralarm

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.db.ScheduledInstanceEntity
import com.calendaralarm.engine.AlarmService
import com.calendaralarm.engine.LARGE_STOP_TAG
import com.calendaralarm.engine.RING_DATE_TAG
import com.calendaralarm.engine.RING_DIAL_TAG
import com.calendaralarm.engine.RING_STARTS_IN_TAG
import com.calendaralarm.engine.RING_TIME_TAG
import com.calendaralarm.engine.RingingActivity
import com.calendaralarm.engine.RingingOverlay
import com.calendaralarm.engine.RingingSlideSpec
import com.calendaralarm.engine.SLIDE_THUMB_TAG
import com.calendaralarm.engine.SLIDE_TRACK_TAG
import com.calendaralarm.engine.SNOOZE_BUTTON_TAG
import com.calendaralarm.engine.TITLE_TAG
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.ui.theme.AppPalette
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 重ねて出す停止画面 (View の RingingOverlay) が、鳴動画面 (Compose の RingingActivity) と
 * 同じ位置・大きさ・色になっていること (#336)。2つは作りが違うので、同じ画面の大きさ (360x740dp) で両方を作って比べる。
 * mdpi なので 1px = 1dp。比べる許容は 2dp (dp を整数の px に丸める差と、輪の大きさの小数の丸めの差)。
 * 天気の背景と札は重ねて出す停止画面に無いので、天気なしで比べる。
 * 文字を持つ部品 (日付・時刻・「○分後に開始」・題) は、文字の幅が描画の書体で変わるので、
 * 左右ではなく中心の横位置と上下で比べる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-mdpi")
class RingingOverlayMatchesScreenTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private val app: CalendarAlarmApp = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<RingingActivity>? = null

    private companion object {
        const val ID = "tm:overlay-compare"
        const val TITLE = "OverlayCompare"
        const val TOL = 2f
        const val WIDTH = 360
        const val HEIGHT = 740
    }

    @After
    fun tearDown() {
        scenario?.close()
        AlarmService.ringingInstanceId.value = null
        runBlocking {
            app.container.settings.setThemeId("default")
            app.container.settings.setLargeButtons(false)
        }
    }

    /** 鳴動画面を出して、始まりまでの 15 分と同じ値を重ねて出す停止画面にも渡す */
    private fun launchScreen(themeId: String, large: Boolean, triggerAt: Long, eventStart: Long) {
        AlarmService.ringingInstanceId.value = null
        runBlocking {
            app.container.settings.setThemeId(themeId)
            app.container.settings.setLargeButtons(large)
            app.container.db.scheduledInstances().upsert(
                listOf(
                    ScheduledInstanceEntity.of(
                        AlarmInstance(ID, triggerAt, TITLE, AlarmKind.EVENT, eventStartMillis = eventStart),
                    ),
                ),
            )
        }
        scenario = ActivityScenario.launch(RingingActivity.intent(app, ID))
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(RING_DATE_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                // 大きいボタンの設定は DataStore から非同期に届くので、反映後にだけ出る目印まで待つ
                compose.onAllNodesWithTag(if (large) LARGE_STOP_TAG else SLIDE_TRACK_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    /**
     * Robolectric が窓の root に載せている inset (システムバーと画面の切り欠き)。診断の表示だけに使う。
     * Compose が実際に受け取った inset とは別の道 (ViewRootImpl の状態) なので、環境によって食い違う。
     * 比べる前提には使わず、食い違いは notes に残すだけにする (食い違い自体は 2 つの画面の差ではない)。
     */
    private fun reportedInsets(): Insets {
        var result = Insets.NONE
        scenario?.onActivity { activity ->
            val w = ViewCompat.getRootWindowInsets(activity.window.decorView)
            if (w != null) {
                result = w.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            }
        }
        return result
    }

    private fun overlay(
        themeId: String = "default",
        large: Boolean = false,
        triggerAt: Long = System.currentTimeMillis(),
        eventStart: Long? = null,
        now: Long = triggerAt,
        insets: Insets = Insets.NONE,
    ): View {
        val root = RingingOverlay.buildView(
            app, ID, TITLE, 10, listOf(5, 10, 15),
            largeButtons = large,
            triggerAtMillis = triggerAt,
            eventStartMillis = eventStart,
            themeId = themeId,
            nowMillis = { now },
        )
        if (insets != Insets.NONE) {
            val w = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), insets).build()
            ViewCompat.dispatchApplyWindowInsets(root, w)
        }
        root.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, WIDTH, HEIGHT)
        return root
    }

    /** 鳴動画面側の目印の位置 (root からの dp)。 */
    private fun screenBounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** 重ねて出す停止画面側の目印の位置 (root からの dp)。親をたどって足し、ScrollView のスクロール分を引く。 */
    private fun viewBounds(root: View, tag: String): Rect {
        val target = root.findViewWithTag<View>(tag) ?: error("目印 $tag の View が無い")
        var x = 0f
        var y = 0f
        var cur: View = target
        while (cur !== root) {
            x += cur.left
            y += cur.top
            val parent = cur.parent as View
            if (parent is ScrollView) y -= parent.scrollY
            cur = parent
        }
        return Rect(x, y, x + target.width, y + target.height)
    }

    // CI のログは失敗の理由しか出さず、最初のずれで止まると他の部品の値が見えない。
    // 比べた値は全部ここに貯め、最後に「どの部品が何 dp ずれたか」と全部の位置を 1 つのメッセージで出す
    private val diffs = mutableListOf<String>()
    private val trace = mutableListOf<String>()
    private val notes = mutableListOf<String>()

    private fun assertSame(name: String, expected: Float, actual: Float) {
        if (abs(expected - actual) > TOL) {
            diffs += "$name: 鳴動画面=$expected 重ねて出す側=$actual (差 ${actual - expected}、許容 $TOL)"
        }
    }

    private fun record(tag: String, s: Rect, v: Rect) {
        trace += "  $tag: 鳴動画面=$s 重ねて出す側=$v"
    }

    /** 上下左右が合う (文字を持たない部品)。 */
    private fun assertBoxMatches(root: View, tag: String) {
        val s = screenBounds(tag)
        val v = viewBounds(root, tag)
        record(tag, s, v)
        assertSame("$tag の左", s.left, v.left)
        assertSame("$tag の上", s.top, v.top)
        assertSame("$tag の右", s.right, v.right)
        assertSame("$tag の下", s.bottom, v.bottom)
    }

    /** 中心の横位置と上下が合う (文字を持つ部品)。 */
    private fun assertTextBoxMatches(root: View, tag: String) {
        val s = screenBounds(tag)
        val v = viewBounds(root, tag)
        record(tag, s, v)
        assertSame("$tag の中心の横", (s.left + s.right) / 2f, (v.left + v.right) / 2f)
        assertSame("$tag の上", s.top, v.top)
        assertSame("$tag の下", s.bottom, v.bottom)
    }

    /** 中心だけが合う (入れ物の大きさが違う時計の文字)。上下は文字の行の高さと入れ物で違うので比べない。 */
    private fun assertCenterMatches(root: View, tag: String) {
        val s = screenBounds(tag)
        val v = viewBounds(root, tag)
        record(tag, s, v)
        assertSame("$tag の中心の横", (s.left + s.right) / 2f, (v.left + v.right) / 2f)
        assertSame("$tag の中心の縦", (s.top + s.bottom) / 2f, (v.top + v.bottom) / 2f)
    }

    /** 貯めたずれが 1 つも無いこと。あれば、ずれた値と比べた部品の全部の位置を出す。 */
    private fun assertNoDiffs() {
        assertTrue(
            "鳴動画面と重ねて出す停止画面の位置がずれている (${diffs.size} 件):\n" +
                diffs.joinToString("\n") + "\n--- 環境 ---\n" + notes.joinToString("\n") +
                "\n--- 比べた部品の位置 ---\n" + trace.joinToString("\n"),
            diffs.isEmpty(),
        )
    }

    /**
     * 鳴動画面の大きさを確かめ、鳴動画面が実際に避けている端の幅 (inset) を位置から読み取って返す。
     * 返す値は重ねて出す側へ同じく流す。窓の inset は ViewRootImpl の状態と Compose が受け取る値が別の道で、
     * Robolectric では食い違いうるので、「鳴動画面が実際に受け取った値」を前提にする。
     * 上は日付の上端 - 上の余白、左右はスヌーズの左右端 - 横の余白、下は停止の下端から下の文字と余白を引いた残り。
     */
    private fun assertRootSize(stopTag: String, footGapDp: Int): Insets {
        val size = compose.onRoot().fetchSemanticsNode().size
        assertEquals("鳴動画面の幅", WIDTH, size.width)
        assertEquals("鳴動画面の高さ", HEIGHT, size.height)
        val date = screenBounds(RING_DATE_TAG)
        val snooze = screenBounds(SNOOZE_BUTTON_TAG)
        val stop = screenBounds(stopTag)
        val top = (date.top - RingingSlideSpec.SCREEN_PAD_V_DP).roundToInt()
        val left = (snooze.left - RingingSlideSpec.SCREEN_PAD_H_DP).roundToInt()
        val right = (WIDTH - snooze.right - RingingSlideSpec.SCREEN_PAD_H_DP).roundToInt()
        val bottom = (
            HEIGHT - RingingSlideSpec.SCREEN_PAD_V_DP - RingingSlideSpec.FOOT_HEIGHT_DP - footGapDp - stop.bottom
            ).roundToInt()
        val insets = Insets.of(left.coerceAtLeast(0), top.coerceAtLeast(0), right.coerceAtLeast(0), bottom.coerceAtLeast(0))
        notes += "鳴動画面の大きさ: ${size.width}x${size.height}"
        notes += "鳴動画面から読み取った inset (左,上,右,下): $insets (元の値 $left,$top,$right,$bottom)"
        notes += "Robolectric が窓に載せている inset: ${reportedInsets()}"
        return insets
    }

    @Test
    fun `日付と輪と時刻と開始までとスヌーズと停止の位置と大きさが鳴動画面と合う`() {
        val now = System.currentTimeMillis()
        launchScreen("forest", large = false, triggerAt = now, eventStart = now + 15 * 60_000L)
        val insets = assertRootSize(SLIDE_TRACK_TAG, RingingSlideSpec.FOOT_GAP_DP)
        val root = overlay(themeId = "forest", triggerAt = now, eventStart = now + 15 * 60_000L, insets = insets)

        assertTextBoxMatches(root, RING_DATE_TAG)
        assertBoxMatches(root, RING_DIAL_TAG)
        assertCenterMatches(root, RING_TIME_TAG)
        assertTextBoxMatches(root, RING_STARTS_IN_TAG)
        assertTextBoxMatches(root, TITLE_TAG)
        assertBoxMatches(root, SNOOZE_BUTTON_TAG)
        assertBoxMatches(root, SLIDE_TRACK_TAG)
        assertNoDiffs()
    }

    @Test
    fun `大きいボタンでも輪とスヌーズと停止の位置が合う`() {
        val now = System.currentTimeMillis()
        launchScreen("forest", large = true, triggerAt = now, eventStart = now + 15 * 60_000L)
        val insets = assertRootSize(LARGE_STOP_TAG, RingingSlideSpec.LARGE_BUTTON_GAP_DP)
        val root = overlay(
            themeId = "forest", large = true, triggerAt = now, eventStart = now + 15 * 60_000L, insets = insets,
        )

        assertTextBoxMatches(root, RING_DATE_TAG)
        assertBoxMatches(root, RING_DIAL_TAG)
        assertCenterMatches(root, RING_TIME_TAG)
        assertTextBoxMatches(root, RING_STARTS_IN_TAG)
        assertTextBoxMatches(root, TITLE_TAG)
        assertBoxMatches(root, SNOOZE_BUTTON_TAG)
        assertBoxMatches(root, LARGE_STOP_TAG)
        assertNoDiffs()
    }

    @Test
    fun `丸と開始までの色が選んだテーマ色になる`() {
        val root = overlay(themeId = "forest", eventStart = System.currentTimeMillis() + 15 * 60_000L)
        val primary = AppPalette.FOREST.dark.primary.toArgb()

        val thumb = root.findViewWithTag<View>(SLIDE_THUMB_TAG)
        assertEquals("丸の色", primary, (thumb.background as GradientDrawable).color!!.defaultColor)
        assertEquals("開始までの文字の色", primary, (root.findViewWithTag<View>(RING_STARTS_IN_TAG) as TextView).currentTextColor)
    }

    @Test
    fun `既定のテーマでは鳴動画面と同じ琥珀になる`() {
        val root = overlay(themeId = "default")
        val thumb = root.findViewWithTag<View>(SLIDE_THUMB_TAG)
        assertEquals(
            "既定は琥珀 (鳴動画面の CalendarAlarmTheme と同じ)",
            AppPalette.AMBER.dark.primary.toArgb(),
            (thumb.background as GradientDrawable).color!!.defaultColor,
        )
    }

    @Test
    fun `鳴動画面のテーマ色は既定と未知の ID で琥珀になり他は選んだ色になる`() {
        assertEquals(AppPalette.AMBER.dark, AppPalette.forRinging("default"))
        assertEquals(AppPalette.AMBER.dark, AppPalette.forRinging(null))
        assertEquals(AppPalette.AMBER.dark, AppPalette.forRinging("unknown-theme"))
        assertEquals(AppPalette.FOREST.dark, AppPalette.forRinging("forest"))
    }

    @Test
    fun `時刻は鳴る時刻を出し日付が付く`() {
        // 試験を動かす機械の時間帯で作るので、書式の結果が時間帯に左右されない
        val triggerAt = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 16, 7, 5) }.timeInMillis
        val root = overlay(triggerAt = triggerAt, now = triggerAt + 3 * 60 * 60_000L)

        assertEquals("7:05", (root.findViewWithTag<View>(RING_TIME_TAG) as TextView).text.toString())
        assertEquals("11月16日 (月)", (root.findViewWithTag<View>(RING_DATE_TAG) as TextView).text.toString())
    }

    @Test
    fun `開始の時刻が無い時はまもなく開始`() {
        val root = overlay(eventStart = null)
        assertEquals(
            app.getString(R.string.ring_starts_soon),
            (root.findViewWithTag<View>(RING_STARTS_IN_TAG) as TextView).text.toString(),
        )
    }

    @Test
    fun `開始まで15分の時は15分後に開始、開始を過ぎていればまもなく開始`() {
        val now = 1_000_000_000_000L
        val before = overlay(triggerAt = now, eventStart = now + 15 * 60_000L, now = now)
        assertEquals(
            app.resources.getQuantityString(R.plurals.ring_starts_in_minutes, 15, 15),
            (before.findViewWithTag<View>(RING_STARTS_IN_TAG) as TextView).text.toString(),
        )
        val after = overlay(triggerAt = now, eventStart = now - 1_000L, now = now)
        assertEquals(
            app.getString(R.string.ring_starts_soon),
            (after.findViewWithTag<View>(RING_STARTS_IN_TAG) as TextView).text.toString(),
        )
    }

    @Test
    fun `背景は透けない`() {
        val root = overlay()
        assertEquals(RingingSlideSpec.BACKGROUND_ARGB, (root.background as ColorDrawable).color)
    }
}
