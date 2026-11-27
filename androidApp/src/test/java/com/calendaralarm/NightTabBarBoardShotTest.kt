package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.calendaralarm.ui.NIGHT_TAB_BAR_TAG
import com.calendaralarm.ui.NightTabBar
import com.calendaralarm.ui.NightTabItem
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #352: 4つのタブを選んだ帯をダークとライトで描く (360x740dp)。
 * BOARD_SHOT_DIR がある時だけ PNG を書き出す (tab-agenda-dark.png 〜 tab-settings-light.png)。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h740dp-mdpi")
class NightTabBarBoardShotTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private val items = listOf(
        NightTabItem("agenda", R.string.tab_agenda, Icons.AutoMirrored.Filled.List),
        NightTabItem("alarms", R.string.tab_alarms, Icons.Default.Alarm),
        NightTabItem("timer", R.string.tab_timer, Icons.Default.Timer),
        NightTabItem("settings", R.string.tab_settings, Icons.Default.Settings),
    )

    private fun shot(route: String, dark: Boolean) {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            CalendarAlarmTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    NightTabBar(items, route, {}, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(NIGHT_TAB_BAR_TAG).assertExists()
        BoardShot.save(activity, "tab-$route-${if (dark) "dark" else "light"}")
        // 次の1枚で根が2つにならないよう、描き終えた Activity は壊す
        controller.pause().stop().destroy()
    }

    @Test
    fun 四つのタブ() {
        for (dark in listOf(true, false)) {
            for (item in items) shot(item.route, dark)
        }
    }
}
