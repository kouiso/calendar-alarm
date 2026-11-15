package com.calendaralarm

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assert
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.ui.TroubleshootingScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #153 の確かめ方「権限OFF → その行が要対応で出る → タップで設定画面へ飛ぶ」を、
 * TroubleshootingScreen を実際に描画して確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h800dp-xxhdpi")
class TroubleshootingFlagTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun show(rowTag: String) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme { TroubleshootingScreen(SettingsRepository(app), onBack = {}) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun calendarPermissionOffIsFlaggedAndTapOpensAppDetails() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        show("troubleshoot_calendar")

        compose.onNodeWithTag("troubleshoot_calendar")
            .assertHasClickAction()
            .assert(hasContentDescription("要対応"))
        assertEquals(1, compose.onAllNodesWithText("カレンダー権限がありません").fetchSemanticsNodes().size)

        compose.onNodeWithTag("troubleshoot_calendar").performClick()
        compose.waitForIdle()

        val started: Intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${app.packageName}", started.dataString)
    }

    @Test
    fun calendarPermissionOnIsNotFlaggedAndTapDoesNothing() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        show("troubleshoot_calendar")

        compose.onNodeWithTag("troubleshoot_calendar").assertHasNoClickAction()
        assertEquals(1, compose.onAllNodesWithText("カレンダー権限あり").fetchSemanticsNodes().size)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun notificationsOffIsFlaggedAndTapOpensAppNotificationSettings() {
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        show("troubleshoot_notifications")

        compose.onNodeWithTag("troubleshoot_notifications")
            .assertHasClickAction()
            .assert(hasContentDescription("要対応"))

        compose.onNodeWithTag("troubleshoot_notifications").performClick()
        compose.waitForIdle()

        val started: Intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(app.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
