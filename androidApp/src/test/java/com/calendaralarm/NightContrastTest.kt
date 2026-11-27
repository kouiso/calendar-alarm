package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 板 (CSS) の面と文字の色が、ダーク・ライトのどちらでも読める明るさの差 (4.5 以上) を持つ事を固定する。
 * 値そのものも CSS の変数と同じである事を確かめる。今の値を固定する試験なので、直す前も通る。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h740dp-mdpi")
class NightContrastTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private fun scheme(dark: Boolean): ColorScheme {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        var captured: ColorScheme? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            CalendarAlarmTheme(darkTheme = dark) { captured = MaterialTheme.colorScheme }
        }
        compose.waitForIdle()
        return captured!!
    }

    private fun ratio(a: Color, b: Color): Double {
        val l1 = maxOf(a.luminance(), b.luminance()).toDouble()
        val l2 = minOf(a.luminance(), b.luminance()).toDouble()
        return (l1 + 0.05) / (l2 + 0.05)
    }

    private fun check(cs: ColorScheme, bg: Long, card: Long, tx: Long, mu: Long) {
        assertEquals(Color(bg), cs.background)
        assertEquals(Color(card), cs.surfaceContainerLow)
        assertEquals(Color(tx), cs.onSurface)
        assertEquals(Color(mu), cs.onSurfaceVariant)
        val pairs = listOf(
            cs.onSurface to cs.background,
            cs.onSurface to cs.surfaceContainerLow,
            cs.onSurfaceVariant to cs.background,
            cs.onSurfaceVariant to cs.surfaceContainerLow,
        )
        for ((fg, face) in pairs) {
            assertTrue("コントラスト比 ${ratio(fg, face)}", ratio(fg, face) >= 4.5)
        }
    }

    @Test
    fun テーマの文字と補助の文字は背景と面に対して読める明るさの差がある_ダーク() {
        check(scheme(true), 0xFF0A0C11, 0xFF12151C, 0xFFECEEF3, 0xFF9AA3B2)
    }

    @Test
    fun テーマの文字と補助の文字は背景と面に対して読める明るさの差がある_ライト() {
        check(scheme(false), 0xFFF4F5F8, 0xFFFFFFFF, 0xFF14171F, 0xFF5B6372)
    }
}
