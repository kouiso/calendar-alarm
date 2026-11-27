package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.calendaralarm.ui.AppPermission
import com.calendaralarm.ui.PermissionsScreen
import com.calendaralarm.ui.permRowTag
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
 * #348: 板 d68/perm.dc.html の列1〜3 (何も無い・一部・全部) をダークとライトで描く。
 * BOARD_SHOT_DIR がある時だけ PNG を書き出す (perm-col1-dark.png 〜 perm-col3-light.png)。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h740dp-mdpi")
class PermissionsBoardShotTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private fun shot(name: String, dark: Boolean, state: Map<AppPermission, Boolean>) {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            CalendarAlarmTheme(darkTheme = dark) { PermissionsScreen(onBack = {}, probe = { state }) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(permRowTag(AppPermission.CALENDAR)).assertExists()
        BoardShot.save(activity, "$name-${if (dark) "dark" else "light"}")
    }

    @Test
    fun 列1から列3() {
        val none = AppPermission.entries.associateWith { false }
        val some = AppPermission.entries.associateWith { it == AppPermission.CALENDAR || it == AppPermission.NOTIFY }
        val allOn = AppPermission.entries.associateWith { true }
        for (dark in listOf(true, false)) {
            shot("perm-col1", dark, none)
            shot("perm-col2", dark, some)
            shot("perm-col3", dark, allOn)
        }
    }
}
