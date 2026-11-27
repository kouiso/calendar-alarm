package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calendaralarm.ui.NIGHT_TAB_BAR_TAG
import com.calendaralarm.ui.NightTabBar
import com.calendaralarm.ui.NightTabItem
import com.calendaralarm.ui.nightTabIndicatorTag
import com.calendaralarm.ui.nightTabTag
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
 * #352: 下タブの帯 (高さ 72・印の後ろの面 56x30・選択中の太さと色・選択の意味付け) が板 .tab の値になっている事を確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h740dp-mdpi")
class NightTabBarTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private val tabItems = listOf(
        NightTabItem("agenda", R.string.tab_agenda, Icons.AutoMirrored.Filled.List),
        NightTabItem("alarms", R.string.tab_alarms, Icons.Default.Alarm),
        NightTabItem("timer", R.string.tab_timer, Icons.Default.Timer),
        NightTabItem("settings", R.string.tab_settings, Icons.Default.Settings),
    )

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { CalendarAlarmTheme(darkTheme = true) { content() } }
        compose.waitForIdle()
    }

    private fun showBar(current: String = "alarms", onSelect: (String) -> Unit = {}) =
        setContent { NightTabBar(tabItems, current, onSelect) }

    @Test
    fun 帯は高さ72で選択中の印の面は56x30() {
        showBar()
        compose.onNodeWithTag(NIGHT_TAB_BAR_TAG).assertHeightIsEqualTo(72.dp)
        compose.onNodeWithTag(nightTabIndicatorTag("alarms")).assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(30.dp)
    }

    @Test
    fun 選択中は1つだけ() {
        showBar()
        compose.onNodeWithTag(nightTabTag("alarms")).assertIsSelected()
        for (route in listOf("agenda", "timer", "settings")) {
            compose.onNodeWithTag(nightTabTag(route)).assertIsNotSelected()
        }
    }

    @Test
    fun 選択中の文字は太い() {
        showBar()
        fun weightOf(text: String): FontWeight? {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            return results.firstOrNull()?.layoutInput?.style?.fontWeight
        }
        assertEquals(FontWeight.SemiBold, weightOf("アラーム"))
        assertEquals(FontWeight.Medium, weightOf("予定"))
    }

    @Test
    fun 押すと経路を渡す() {
        val picked = mutableListOf<String>()
        showBar(onSelect = { picked.add(it) })
        compose.onNodeWithText("タイマー").performClick()
        assertEquals(listOf("timer"), picked)
    }

    @Test
    fun 中身の一番下が帯に隠れない() {
        setContent {
            Scaffold(bottomBar = { NightTabBar(tabItems, "agenda", {}) }) { p ->
                LazyColumn(Modifier.padding(p)) {
                    items((0 until 50).toList()) { Text("行$it", Modifier.fillMaxWidth().height(48.dp)) }
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToIndex(49)
        compose.waitForIdle()
        val last = compose.onNodeWithText("行49").getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag(NIGHT_TAB_BAR_TAG).getUnclippedBoundsInRoot()
        assertTrue("行49の下端 ${last.bottom} が帯の上端 ${bar.top} 以下", last.bottom.value <= bar.top.value + 1f)
    }

    @Test
    fun 名前は二重に読まれない() {
        showBar()
        compose.onAllNodes(
            hasAnyAncestor(hasTestTag(nightTabTag("agenda"))) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        assertEquals(1, compose.onAllNodesWithText("予定").fetchSemanticsNodes().size)
    }
}
