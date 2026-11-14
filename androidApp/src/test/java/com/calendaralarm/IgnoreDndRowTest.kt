package com.calendaralarm

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.settingsDataStore
import com.calendaralarm.ui.CRESCENT_MOON_PATH
import com.calendaralarm.ui.CrescentMoonIcon
import com.calendaralarm.ui.IGNORE_DND_ICON_TAG
import com.calendaralarm.ui.IGNORE_DND_OPEN_PERMISSION_TAG
import com.calendaralarm.ui.IGNORE_DND_PREVIEW_TAG
import com.calendaralarm.ui.IgnoreDndRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 「おやすみモード中も鳴らす」の行は盤 p149 のとおり: 既定オン、許可が無い間はスイッチを押せず「許可を開く」を出す (#149)。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ja")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IgnoreDndRowTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private fun show(checked: Boolean, policyGranted: Boolean, onChange: (Boolean) -> Unit = {}, onOpen: () -> Unit = {}) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme { IgnoreDndRow(checked, policyGranted, onChange, onOpen) }
        }
        compose.waitForIdle()
    }

    @Test
    fun `見出しは盤の文言で許可があればスイッチは押せる`() {
        var changed: Boolean? = null
        show(checked = true, policyGranted = true, onChange = { changed = it })
        compose.onNodeWithText("おやすみモード中も鳴らす").assertExists()
        compose.onNodeWithText("サイレントにしていても、アラームだけは鳴ります").assertExists()
        compose.onNodeWithText("許可を開く").assertDoesNotExist()
        compose.onNode(isToggleable() and hasContentDescription("おやすみモード中も鳴らす")).assertExists()
        compose.onNode(isToggleable()).assertIsEnabled().assertIsOn().performClick()
        assertEquals(false, changed)
    }

    private fun assertPreviewBox(checked: Boolean) {
        show(checked = checked, policyGranted = true)
        compose.onNodeWithTag(IGNORE_DND_PREVIEW_TAG).assertExists()
        compose.onNode(hasText("オフにすると") and hasAnyAncestor(hasTestTag(IGNORE_DND_PREVIEW_TAG))).assertExists()
        compose.onNode(
            hasText("おやすみモード中は鳴らさず、通知だけ残す") and hasAnyAncestor(hasTestTag(IGNORE_DND_PREVIEW_TAG)),
        ).assertExists()
        // 説明の行は盤どおり常に ON の文 (OFF の時の差し替えは盤に無い)
        compose.onNodeWithText("サイレントにしていても、アラームだけは鳴ります").assertExists()
        compose.onNode(isToggleable()).assertIsEnabled()
        if (checked) compose.onNode(isToggleable()).assertIsOn() else compose.onNode(isToggleable()).assertIsOff()
    }

    @Test
    fun `許可があって ON ならカードの下にオフにするとの箱を盤の文言で出す`() = assertPreviewBox(checked = true)

    @Test
    fun `許可があって OFF でも箱を出し説明は ON の文のまま`() = assertPreviewBox(checked = false)

    @Test
    fun `許可が無い間は箱を出さない`() {
        show(checked = true, policyGranted = false)
        compose.onNodeWithTag(IGNORE_DND_PREVIEW_TAG).assertDoesNotExist()
        compose.onNodeWithText("オフにすると").assertDoesNotExist()
    }

    private lateinit var shownActivity: ComponentActivity

    /** タグのノードの範囲を、画面全体を Bitmap へ描いた中から切り出して ARGB の並びで返す。 */
    private fun pixelsOf(tag: String): Pair<IntArray, Int> {
        val view = shownActivity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val r = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val w = r.width.toInt()
        val h = r.height.toInt()
        val out = IntArray(w * h)
        bmp.getPixels(out, 0, w, r.left.toInt(), r.top.toInt(), w, h)
        return out to w
    }

    private fun iconMatchesCrescent(policyGranted: Boolean, tint: (androidx.compose.material3.ColorScheme) -> androidx.compose.ui.graphics.Color) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shownActivity = activity
        activity.setContent {
            MaterialTheme {
                Column {
                    IgnoreDndRow(true, policyGranted, {}, {})
                    // 期待: 盤の三日月を、許可の有無で決まる色で 20dp に描いたもの
                    Icon(
                        CrescentMoonIcon,
                        contentDescription = null,
                        tint = tint(MaterialTheme.colorScheme),
                        modifier = Modifier.size(20.dp).testTag("expected_icon"),
                    )
                }
            }
        }
        compose.waitForIdle()
        val (actual, _) = pixelsOf(IGNORE_DND_ICON_TAG)
        assertTrue("アイコンに色が載っていること", actual.any { (it ushr 24) != 0 })
        val expected = pixelsOf("expected_icon").first
        assertEquals(expected.size, actual.size)
        // 輪郭のにじみは描く位置で 1 段階ずれるので、各チャンネルの差が小さければ同じ絵とみなす
        val worst = expected.indices.maxOf { i ->
            (0..3).maxOf { sh -> kotlin.math.abs(((expected[i] ushr (sh * 8)) and 0xFF) - ((actual[i] ushr (sh * 8)) and 0xFF)) }
        }
        assertTrue("三日月の形と色が盤どおり (最大差 $worst)", worst <= 6)
    }

    @Test
    fun `アイコンは盤の三日月の path で作る`() {
        assertEquals("M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z", CRESCENT_MOON_PATH)
        assertEquals("CrescentMoon", CrescentMoonIcon.name)
        assertEquals(24f, CrescentMoonIcon.viewportWidth)
        assertEquals(24f, CrescentMoonIcon.viewportHeight)
    }

    @Test
    fun `許可があればアイコンは三日月でアクセント色`() = iconMatchesCrescent(true) { it.primary }

    @Test
    fun `許可が無ければアイコンは三日月で薄い色`() = iconMatchesCrescent(false) { it.outline }

    @Test
    fun `許可を開くは盤の btn で塗りつぶしのアクセント色で幅いっぱい高さ52dp`() {
        var scheme: androidx.compose.material3.ColorScheme? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shownActivity = activity
        activity.setContent {
            MaterialTheme {
                scheme = MaterialTheme.colorScheme
                Box(Modifier.width(300.dp)) { IgnoreDndRow(true, false, {}, {}) }
            }
        }
        compose.waitForIdle()
        val node = compose.onNodeWithTag(IGNORE_DND_OPEN_PERMISSION_TAG)
        val bounds = node.getUnclippedBoundsInRoot()
        assertEquals(52f, (bounds.bottom - bounds.top).value, 0.01f)
        assertEquals(300f - 32f, (bounds.right - bounds.left).value, 0.01f)
        // 左端の中央 (角丸 26 の内側で文字より外) の色が、塗りつぶしのアクセント色であること
        val (px, w) = pixelsOf(IGNORE_DND_OPEN_PERMISSION_TAG)
        val left = px[(px.size / w / 2) * w + 6]
        assertEquals(scheme!!.primary.toArgb(), left)
    }

    @Test
    fun `許可が無い間は保存値がONでもスイッチはOFFで押せず許可を開くが出る`() {
        var changed: Boolean? = null
        var opened = 0
        show(checked = true, policyGranted = false, onChange = { changed = it }, onOpen = { opened++ })
        compose.onNode(isToggleable()).assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("使うには「おやすみモードの例外」の許可が要ります。").assertExists()
        compose.onNode(hasText("許可を開く") and hasClickAction()).assertIsEnabled().performClick()
        assertEquals(1, opened)
        assertEquals(null, changed)
    }

    @Test
    fun `許可が無い間のスイッチは何のスイッチで許可が要ると読み上げる`() {
        show(checked = true, policyGranted = false)
        compose.onNode(isToggleable() and hasContentDescription("おやすみモード中も鳴らす（許可が必要）")).assertExists()
    }

    @Test
    fun `保存値が無い時の既定はオン`() {
        assertTrue(SettingsRepository.Settings().ignoreDnd)
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 同じ JVM の別のテストが保存した値が残っていても、保存値が「無い」状態で読ませる
        runBlocking { context.settingsDataStore.edit { it.remove(SettingsRepository.KEY_IGNORE_DND) } }
        assertTrue(runBlocking { SettingsRepository(context).flow.first() }.ignoreDnd)
    }
}
