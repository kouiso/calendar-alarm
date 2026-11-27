package com.calendaralarm

import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.ui.AppPermission
import com.calendaralarm.ui.PERM_GRANT_ALL_TAG
import com.calendaralarm.ui.PERM_SUMMARY_ICON_TAG
import com.calendaralarm.ui.PermissionsScreen
import com.calendaralarm.ui.permRowTag
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import com.calendaralarm.ui.theme.NIGHT_DIVIDER_TAG
import com.calendaralarm.ui.theme.NIGHT_TOP_BAR_BACK_TAG
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #348: 権限画面の形 (面・区切り線・戻る・まとめの印・ボタン) が板 d68/perm.dc.html の値になっている事を確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h740dp-mdpi")
class PermissionsShapeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun show(state: Map<AppPermission, Boolean>, onBack: () -> Unit = {}): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            CalendarAlarmTheme(darkTheme = true) { PermissionsScreen(onBack = onBack, probe = { state }) }
        }
        compose.waitForIdle()
        return activity
    }

    private fun all(on: Boolean) = AppPermission.entries.associateWith { on }

    @Test
    fun 行の間に線が3本() {
        show(all(false))
        assertEquals(3, compose.onAllNodesWithTag(NIGHT_DIVIDER_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun まとめの印は40でボタンは高さ52() {
        show(all(false))
        compose.onNodeWithTag(PERM_SUMMARY_ICON_TAG).assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
        compose.onNodeWithTag(PERM_GRANT_ALL_TAG).assertHeightIsEqualTo(52.dp)
    }

    @Test
    fun 全部許可済みではボタンが無い() {
        show(all(true))
        assertEquals(0, compose.onAllNodesWithTag(PERM_GRANT_ALL_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun 戻るは44で押すと戻る() {
        var backed = 0
        show(all(false), onBack = { backed++ })
        compose.onNodeWithTag(NIGHT_TOP_BAR_BACK_TAG).assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
        compose.onNodeWithTag(NIGHT_TOP_BAR_BACK_TAG).performClick()
        assertEquals(1, backed)
    }

    @Test
    fun 行を押すと今までどおり頼む() {
        show(AppPermission.entries.associateWith { it != AppPermission.CALENDAR })
        val description = app.getString(
            R.string.perm_open_settings_for,
            app.getString(R.string.perm_calendar_label),
            app.getString(R.string.perm_state_missing),
        )
        compose.onNodeWithTag(permRowTag(AppPermission.CALENDAR))
            .assertHasClickAction()
            .assert(hasContentDescription(description))
    }

    @Test
    fun 許可済みの行を押すと端末の設定を開く() {
        val activity = show(all(true))
        compose.onNodeWithTag(permRowTag(AppPermission.CALENDAR)).performClick()
        // カレンダーは個別の設定画面を持たないので、アプリ詳細の設定へ倒れる
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
    }
}
