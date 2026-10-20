package com.calendaralarm

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.calendaralarm.ai.AiConfig
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.settingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiKeyRemovalTest {

    @Test
    fun `キーが無いか空白だけならAIの入口を出さない`() {
        // 開発者が手元でキーを渡したビルドでも通るよう、ビルドのキーではなく試験用の値で確かめる
        assertNull(AiConfig.apiFor(""))
        assertNull(AiConfig.apiFor("   "))
        assertNotNull(AiConfig.apiFor("sk-test"))
    }

    @Test
    fun `旧版で保存されたAIキーは端末から消す`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val key = stringPreferencesKey("openrouter_api_key")
        context.settingsDataStore.edit { it[key] = "sk-legacy" }
        SettingsRepository(context).dropLegacyAiKey()
        assertNull(context.settingsDataStore.data.first()[key])
    }
}
