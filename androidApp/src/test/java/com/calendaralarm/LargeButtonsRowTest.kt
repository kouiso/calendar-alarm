package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calendaralarm.ui.LargeButtonsRow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 「停止ボタンを大きくする」は、見出しとスイッチが 1 つの切り替えとして読み上げられ、行を押すと切り替わる (#154)。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LargeButtonsRowTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    @Test
    fun `見出しの付いた切り替えとして読み上げられ、押すと切り替わる`() {
        var changed: Boolean? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { MaterialTheme { LargeButtonsRow(checked = false) { changed = it } } }
        compose.waitForIdle()
        // 見出しを持つ部品そのものが切り替え (スイッチだけが別の部品だと、読み上げで何のスイッチか分からない)
        compose.onNodeWithText("停止ボタンを大きくする").assertIsToggleable().assertIsOff()
        compose.onNodeWithText("停止ボタンを大きくする").performClick()
        assertEquals(true, changed)
    }
}
