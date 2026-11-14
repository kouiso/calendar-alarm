package com.calendaralarm

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.calendaralarm.shared.model.InviteFilter
import com.calendaralarm.shared.model.InviteStatus
import com.calendaralarm.ui.InviteFilterRow
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
 * 設定の招待予定のチップ (出席・未定・未回答・辞退) が、狭い画面と大きい文字でも全部見えて押せる (#204)。
 * 文字幅を実測させるため NATIVE グラフィックスで描く。外側の余白は設定画面のカード (画面 16dp + カード 14dp) と同じにする。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 1.3f)
class InviteFilterChipLayoutTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private val labels = listOf("出席", "未定", "未回答", "辞退")

    private fun show(onToggle: (InviteStatus) -> Unit = {}) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp)) {
                    InviteFilterRow(InviteFilter(), onToggle)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `大きい文字でも4枚とも画面の中に収まり、文字が潰れない`() {
        show()
        val rootRight = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
        labels.forEach { label ->
            val b = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$label が画面の右端からはみ出さないこと (right=${b.right} root=$rootRight)", b.right <= rootRight)
            // 1 文字ずつに潰れると高さが文字数ぶん伸び、幅が 1 文字ぶんになる
            assertTrue("$label の幅が潰れないこと (width=${b.width} height=${b.height})", b.width > b.height)
        }
    }

    @Test
    fun `右端の辞退も押せて、辞退を切り替える`() {
        var toggled: InviteStatus? = null
        show { toggled = it }
        compose.onNodeWithText("辞退").performClick()
        assertEquals(InviteStatus.DECLINED, toggled)
    }
}
