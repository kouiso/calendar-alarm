package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import com.calendaralarm.ui.theme.NightIconRings
import kotlinx.coroutines.test.StandardTestDispatcher
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
        // 60 の丸 + 外の余白 10.5 ずつ。外の丸 (半径 40 + 線の外側 0.5) が箱に収まる大きさ
        compose.onNodeWithTag("rings").assertWidthIsEqualTo(81.dp).assertHeightIsEqualTo(81.dp)
    }
}
