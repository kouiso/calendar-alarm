package com.calendaralarm

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calendaralarm.ui.THEME_SWATCH_TAG_PREFIX
import com.calendaralarm.ui.ThemeSwatchRow
import com.calendaralarm.ui.swatchColor
import com.calendaralarm.ui.theme.AppPalette
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 設定のテーマの丸 (#327)。保存値が "default" の時は琥珀に印が付き、
 * 丸は今の外観 (ライト/ダーク) で実際に使う色で塗られることを確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h740dp-notnight-mdpi")
class ThemeSwatchRowTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private var activity: ComponentActivity? = null

    private fun show(themeId: String, onSelect: (AppPalette) -> Unit = {}) {
        val a = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity = a
        a.setContent {
            MaterialTheme { ThemeSwatchRow(themeId, onSelect) }
        }
        compose.waitForIdle()
    }

    private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    private fun Color.rgb() = rgb(toArgb())

    /** タグ theme_swatch_ocean の丸の左寄り (円の内側) の 1 画素を取る。 */
    private fun oceanPixel(): Int {
        val bounds = compose.onNodeWithTag(THEME_SWATCH_TAG_PREFIX + AppPalette.OCEAN.id, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        val decor = activity!!.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        return bitmap.getPixel((bounds.left + bounds.width * 0.12f).toInt(), bounds.center.y.toInt())
    }

    @Test
    fun `一度も選んでいない時は琥珀に印が付く`() {
        show("default")
        compose.onNodeWithText("琥珀").assertIsSelected()
        compose.onNodeWithText("インディゴ").assertIsNotSelected()
        assertEquals(1, compose.onAllNodesWithContentDescription("選択中", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `選んだ色にだけ印が付く`() {
        show("indigo")
        compose.onNodeWithText("インディゴ").assertIsSelected()
        compose.onNodeWithText("琥珀").assertIsNotSelected()
    }

    @Test
    fun `知らないIDは琥珀に印が付く`() {
        show("unknown")
        compose.onNodeWithText("琥珀").assertIsSelected()
    }

    @Test
    fun `押した色を渡す`() {
        var picked: AppPalette? = null
        show("default") { picked = it }
        compose.onNodeWithText("インディゴ").performClick()
        assertEquals(AppPalette.INDIGO, picked)
    }

    @Config(sdk = [34], qualifiers = "ja-w360dp-h740dp-night-mdpi")
    @Test
    fun `ダークの時は丸をダーク用の色で塗る`() {
        show("default")
        val dark = AppPalette.OCEAN.dark.primary
        val light = AppPalette.OCEAN.light.primary
        assertNotEquals("前提: ライトとダークで primary が違うこと", dark, light)
        val actual = oceanPixel()
        assertEquals("丸の色がダーク用であること (actual=${rgb(actual)})", dark.rgb(), rgb(actual))
    }

    @Test
    fun `ライトの時は丸をライト用の色で塗る`() {
        show("default")
        val actual = oceanPixel()
        assertEquals(
            "丸の色がライト用であること (actual=${rgb(actual)})",
            AppPalette.OCEAN.light.primary.rgb(),
            rgb(actual),
        )
    }

    @Test
    fun `swatchColorは外観でダークとライトを選ぶ`() {
        assertEquals(AppPalette.AMBER.dark.primary, swatchColor(AppPalette.AMBER, true))
        assertEquals(AppPalette.AMBER.light.primary, swatchColor(AppPalette.AMBER, false))
    }
}
