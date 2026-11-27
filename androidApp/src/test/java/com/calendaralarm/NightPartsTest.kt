package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import com.calendaralarm.ui.theme.NightIconRings
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * NightParts.kt の部品のうち、今ある物の形を固定する。
 * (#338 の NightChip・NightBanner などは #338 側で足す。ここは #348・#350・#352 が使う分だけ)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h740dp-mdpi")
class NightPartsTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    @Test
    fun 印の輪は外の丸の線が切られない81() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            CalendarAlarmTheme(darkTheme = true) {
                NightIconRings(
                    icon = Icons.Default.Check,
                    ringColor = Color.White,
                    iconTint = Color.White,
                    modifier = Modifier.testTag("rings"),
                )
            }
        }
        compose.waitForIdle()
        // 60 の丸 + 外の余白 10.5 ずつ。外の丸 (半径 40 + 線の外側 0.5) が箱に収まる大きさ。
        // 以前は padding(10.5.dp) が px へ四捨五入されて 82 になっていた。値を出して落とす
        val b = compose.onNodeWithTag("rings").getUnclippedBoundsInRoot()
        assertEquals("印の輪の幅 (期待 81dp・実測 ${b.width.value}dp)", 81f, b.width.value, 0.01f)
        assertEquals("印の輪の高さ (期待 81dp・実測 ${b.height.value}dp)", 81f, b.height.value, 0.01f)
    }
}
