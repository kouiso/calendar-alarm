package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Smartphone
import com.calendaralarm.ui.ASK_BODY_TAG
import com.calendaralarm.ui.ASK_CTA_TAG
import com.calendaralarm.ui.ASK_ICON_TAG
import com.calendaralarm.ui.ASK_LATER_TAG
import com.calendaralarm.ui.ASK_READY_ICON
import com.calendaralarm.ui.ASK_TITLE_TAG
import com.calendaralarm.ui.AppPermission
import com.calendaralarm.ui.AskIconTone
import com.calendaralarm.ui.AskSheetBody
import com.calendaralarm.ui.askIcon
import com.calendaralarm.ui.askIconTone
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #350: 許可を頼む面の中身 (印の二重の輪・ボタン・「あとで」) の形が板 d68/ask.dc.html の値になっている事を確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h740dp-mdpi")
class AskSheetShapeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private fun show(onCta: () -> Unit = {}, onLater: () -> Unit = {}) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            CalendarAlarmTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    AskSheetBody(
                        title = activity.getString(R.string.perm_calendar_ask_title),
                        body = activity.getString(R.string.perm_calendar_ask_body),
                        cta = activity.getString(R.string.ask_allow),
                        later = activity.getString(R.string.ask_later),
                        icon = AppPermission.CALENDAR.askIcon(),
                        tone = AskIconTone.ACCENT,
                        onCta = onCta,
                        onLater = onLater,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun 印の輪とボタンとあとでの大きさ() {
        show()
        compose.onNodeWithTag(ASK_ICON_TAG).assertWidthIsEqualTo(78.dp).assertHeightIsEqualTo(78.dp)
        compose.onNodeWithTag(ASK_CTA_TAG).assertHeightIsEqualTo(52.dp)
        compose.onNodeWithTag(ASK_LATER_TAG).assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun 部品の間は12() {
        show()
        val icon = compose.onNodeWithTag(ASK_ICON_TAG).getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag(ASK_TITLE_TAG).getUnclippedBoundsInRoot()
        val body = compose.onNodeWithTag(ASK_BODY_TAG).getUnclippedBoundsInRoot()
        val cta = compose.onNodeWithTag(ASK_CTA_TAG).getUnclippedBoundsInRoot()
        assertEquals(12f, (title.top - icon.bottom).value, 1f)
        assertEquals(12f, (cta.top - body.bottom).value, 1f)
    }

    @Test
    fun 頼み直しは赤い輪で準備OKは緑の輪() {
        assertEquals(AskIconTone.WARN, askIconTone(retry = true, ready = false))
        assertEquals(AskIconTone.OK, askIconTone(retry = false, ready = true))
        assertEquals(AskIconTone.ACCENT, askIconTone(retry = false, ready = false))
    }

    @Test
    fun 許可ごとの絵() {
        assertTrue(AppPermission.CALENDAR.askIcon() === Icons.Outlined.CalendarToday)
        assertTrue(AppPermission.NOTIFY.askIcon() === Icons.Outlined.Notifications)
        assertTrue(AppPermission.EXACT.askIcon() === Icons.Outlined.Schedule)
        assertTrue(AppPermission.FULL_SCREEN.askIcon() === Icons.Outlined.Smartphone)
        assertTrue(ASK_READY_ICON === Icons.Default.Check)
    }

    @Test
    fun ボタンを押すとonCta_あとでを押すとonLater() {
        var cta = 0
        var later = 0
        show(onCta = { cta++ }, onLater = { later++ })
        compose.onNodeWithTag(ASK_CTA_TAG).performClick()
        assertEquals(1, cta)
        assertEquals(0, later)
        compose.onNodeWithTag(ASK_LATER_TAG).performClick()
        assertEquals(1, later)
    }
}
