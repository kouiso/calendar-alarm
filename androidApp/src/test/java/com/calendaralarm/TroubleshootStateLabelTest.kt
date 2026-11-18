package com.calendaralarm

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.calendaralarm.ui.CheckState
import com.calendaralarm.ui.TroubleshootItem
import com.calendaralarm.ui.TroubleshootRow
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
 * #228「トラブルシューティングの状態を印の色と形だけでなく言葉でも出す」の確かめ方。
 * 各状態の言葉 (OK/要対応/対象外/参考) と「設定を開く」が見えて、
 * 文字を2倍にしても画面の幅に収まることを確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w360dp-h800dp-xxhdpi", fontScale = 2.0f)
class TroubleshootStateLabelTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private fun item(state: CheckState, fix: Intent?, key: String = "x") = TroubleshootItem(
        key = key,
        state = state,
        title = "カレンダー権限がありません",
        description = "予定を見つけ、全画面アラーム通知をスケジュールするために必要です。",
        fix = fix,
    )

    private fun show(item: TroubleshootItem) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth()) { TroubleshootRow(item) {} }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(item.title).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertInsideScreen(text: String) {
        val b = compose.onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("「$text」が画面の幅に収まる (right=${b.right} root=${root.right})", b.right <= root.right)
        assertTrue("「$text」がつぶれていない (width=${b.width} height=${b.height})", b.width > 0 && b.height > 0)
    }

    @Test
    fun `要対応の行は「要対応」と「設定を開く」の言葉が見えて2倍文字でも幅に収まる`() {
        show(item(CheckState.NG, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)))
        assertInsideScreen("要対応")
        assertInsideScreen("設定を開く")
    }

    @Test
    fun `OKの行は「OK」の言葉が見える`() {
        show(item(CheckState.OK, null))
        assertInsideScreen("OK")
    }

    @Test
    fun `対象外の行は「対象外」の言葉が見える`() {
        show(item(CheckState.NA, null))
        assertInsideScreen("対象外")
    }

    @Test
    fun `参考の行は「参考」と「設定を開く」の言葉が見える`() {
        show(item(CheckState.INFO, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)))
        assertInsideScreen("参考")
        assertInsideScreen("設定を開く")
    }

    @Test
    fun `「設定を開く」は行の中で文字として出る (NG・INFO両方)`() {
        // 2行まとめて出し、どちらにも「設定を開く」の文字が付くことを確かめる
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth()) {
                    TroubleshootRow(item(CheckState.NG, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), key = "ng")) {}
                    TroubleshootRow(item(CheckState.INFO, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS), key = "info")) {}
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("設定を開く", useUnmergedTree = true)
                .fetchSemanticsNodes().size == 2
        }
        assertEquals(
            2,
            compose.onAllNodesWithText("設定を開く", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }
}
