package com.calendaralarm

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.settingsDataStore
import com.calendaralarm.ui.IgnoreDndRow
import kotlinx.coroutines.flow.first
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

/** 「おやすみモード中も鳴らす」の行は盤 p149 のとおり: 既定オン、許可が無い間はスイッチを押せず「許可を開く」を出す (#149)。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ja")
class IgnoreDndRowTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private fun show(checked: Boolean, policyGranted: Boolean, onChange: (Boolean) -> Unit = {}, onOpen: () -> Unit = {}) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            MaterialTheme { IgnoreDndRow(checked, policyGranted, onChange, onOpen) }
        }
        compose.waitForIdle()
    }

    @Test
    fun `見出しは盤の文言で許可があればスイッチは押せる`() {
        var changed: Boolean? = null
        show(checked = true, policyGranted = true, onChange = { changed = it })
        compose.onNodeWithText("おやすみモード中も鳴らす").assertExists()
        compose.onNodeWithText("サイレントにしていても、アラームだけは鳴ります").assertExists()
        compose.onNodeWithText("許可を開く").assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsEnabled().assertIsOn().performClick()
        assertEquals(false, changed)
    }

    @Test
    fun `許可が無い間はスイッチを押せず許可を開くが出て押せる`() {
        var changed: Boolean? = null
        var opened = 0
        show(checked = true, policyGranted = false, onChange = { changed = it }, onOpen = { opened++ })
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.onNodeWithText("使うには「おやすみモードの例外」の許可が要ります。").assertExists()
        compose.onNode(hasText("許可を開く") and hasClickAction()).assertIsEnabled().performClick()
        assertEquals(1, opened)
        assertEquals(null, changed)
    }

    @Test
    fun `保存値が無い時の既定はオン`() {
        assertTrue(SettingsRepository.Settings().ignoreDnd)
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 同じ JVM の別のテストが保存した値が残っていても、保存値が「無い」状態で読ませる
        runBlocking { context.settingsDataStore.edit { it.remove(SettingsRepository.KEY_IGNORE_DND) } }
        assertTrue(runBlocking { SettingsRepository(context).flow.first() }.ignoreDnd)
    }
}
