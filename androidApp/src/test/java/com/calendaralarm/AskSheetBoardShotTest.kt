package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.calendaralarm.ui.ASK_CTA_TAG
import com.calendaralarm.ui.ASK_READY_ICON
import com.calendaralarm.ui.AppPermission
import com.calendaralarm.ui.AskIconTone
import com.calendaralarm.ui.AskSheetBody
import com.calendaralarm.ui.askIcon
import com.calendaralarm.ui.askIconTone
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import com.calendaralarm.ui.theme.NightSheetHandle
import com.calendaralarm.ui.theme.NightTokens
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
 * #350: 板 d68/ask.dc.html の列1〜6 (各許可・頼み直し・準備OK) をダークとライトで描く。
 * ModalBottomSheet は別の窓に出て根の画像に入らないので、同じ形の入れ物に面の中身を置く。
 * BOARD_SHOT_DIR がある時だけ PNG を書き出す (ask-col1-dark.png 〜 ask-col6-light.png)。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h740dp-mdpi")
class AskSheetBoardShotTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private fun shot(name: String, dark: Boolean, perm: AppPermission?, retry: Boolean) {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            CalendarAlarmTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Box(Modifier.fillMaxSize().background(NightTokens.scrim))
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        NightSheetHandle()
                        if (perm == null) {
                            AskSheetBody(
                                title = activity.getString(R.string.ask_ready_title),
                                body = activity.getString(R.string.ask_ready_body),
                                cta = activity.getString(R.string.ask_ready_test),
                                later = activity.getString(R.string.common_close),
                                icon = ASK_READY_ICON,
                                tone = askIconTone(retry = false, ready = true),
                                onCta = {},
                                onLater = {},
                            )
                        } else {
                            AskSheetBody(
                                title = activity.getString(if (retry) R.string.ask_still_off else perm.askTitle),
                                body = if (retry) {
                                    activity.getString(
                                        R.string.ask_retry_body,
                                        activity.getString(perm.label),
                                        activity.getString(perm.reason),
                                    )
                                } else {
                                    activity.getString(perm.askBody)
                                },
                                cta = activity.getString(
                                    when {
                                        retry -> R.string.ask_open_settings_again
                                        perm.viaSettings -> R.string.ask_turn_on_in_settings
                                        else -> R.string.ask_allow
                                    },
                                ),
                                later = activity.getString(R.string.ask_later),
                                icon = perm.askIcon(),
                                tone = askIconTone(retry = retry, ready = false),
                                onCta = {},
                                onLater = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(ASK_CTA_TAG).assertExists()
        BoardShot.save(activity, "$name-${if (dark) "dark" else "light"}")
        // 次の1枚で根が2つにならないよう、描き終えた Activity は壊す
        controller.pause().stop().destroy()
    }

    @Test
    fun 列1から列6() {
        for (dark in listOf(true, false)) {
            shot("ask-col1", dark, AppPermission.CALENDAR, retry = false)
            shot("ask-col2", dark, AppPermission.NOTIFY, retry = false)
            shot("ask-col3", dark, AppPermission.EXACT, retry = false)
            shot("ask-col4", dark, AppPermission.FULL_SCREEN, retry = false)
            shot("ask-col5", dark, AppPermission.EXACT, retry = true)
            shot("ask-col6", dark, null, retry = false)
        }
    }
}
