package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calendaralarm.ui.NIGHT_TAB_BAR_TAG
import com.calendaralarm.ui.NightTabBar
import com.calendaralarm.ui.NightTabItem
import com.calendaralarm.ui.nightTabIndicatorTag
import com.calendaralarm.ui.nightTabTag
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import kotlinx.coroutines.runBlocking
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
        val bar = compose.onNodeWithTag(NIGHT_TAB_BAR_TAG).getUnclippedBoundsInRoot()
        assertEquals("帯の高さ (期待 72dp・実測 ${bar.height.value}dp)", 72f, bar.height.value, 0.01f)
        // タブ全体は selectable で意味を束ねる (mergeDescendants) ので、中の印の面の目印は束ねた木には出ない。
        // 束ねない木 (useUnmergedTree) で探す。これを付けないと「ノードが無い」で落ちる
        val indicator = compose.onNodeWithTag(nightTabIndicatorTag("alarms"), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertEquals("印の面の幅 (期待 56dp・実測 ${indicator.width.value}dp)", 56f, indicator.width.value, 0.01f)
        assertEquals("印の面の高さ (期待 30dp・実測 ${indicator.height.value}dp)", 30f, indicator.height.value, 0.01f)
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
        lateinit var listState: LazyListState
        setContent {
            Scaffold(bottomBar = { NightTabBar(tabItems, "agenda", {}) }) { p ->
                listState = rememberLazyListState()
                LazyColumn(Modifier.padding(p).testTag("night-list"), state = listState) {
                    items((0 until 50).toList()) { Text("行$it", Modifier.fillMaxWidth().height(48.dp)) }
                }
            }
        }
        // 意味の動作 (performScrollToIndex) は StandardTestDispatcher に積まれ、走ったか分からない。
        // 状態へ直に最後まで送り、送れた事を値で確かめてから位置を比べる
        compose.runOnIdle { runBlocking { listState.scrollToItem(49) } }
        compose.waitForIdle()
        val info = compose.runOnIdle {
            "先頭 ${listState.firstVisibleItemIndex}・見える数 ${listState.layoutInfo.visibleItemsInfo.size}・" +
                "全部 ${listState.layoutInfo.totalItemsCount}・窓 ${listState.layoutInfo.viewportSize}"
        }
        val found = compose.onAllNodesWithText("行49").fetchSemanticsNodes().size
        assertEquals("「行49」が木に ${found} 個 (期待 1)。$info", 1, found)
        val last = compose.onNodeWithText("行49").getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag(NIGHT_TAB_BAR_TAG).getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("night-list").getUnclippedBoundsInRoot()
        assertTrue(
            "行49の下端 ${last.bottom} が帯の上端 ${bar.top} 以下 (一覧 ${list.top}〜${list.bottom})。$info",
            last.bottom.value <= bar.top.value + 1f,
        )
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
