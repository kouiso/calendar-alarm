package com.calendaralarm.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** アプリ全体設定。DataStore Preferences で保持する。 */
class SettingsRepository(private val context: Context) {

    data class Settings(
        val defaultMinutesBefore: Int = 0,
        val defaultSnoozeMinutes: Int = 10,
        val weatherEnabled: Boolean = true,
        val onboardingDone: Boolean = false,
        /** ユーザー選択のアラーム音 (SAF URI)。null=端末デフォルト。 */
        val defaultSoundUri: String? = null,
    )

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            defaultMinutesBefore = p[KEY_MINUTES] ?: 0,
            defaultSnoozeMinutes = p[KEY_SNOOZE] ?: 10,
            weatherEnabled = p[KEY_WEATHER] ?: true,
            onboardingDone = p[KEY_ONBOARDED] ?: false,
            defaultSoundUri = p[KEY_SOUND],
        )
    }

    suspend fun setDefaultMinutesBefore(v: Int) = edit { it[KEY_MINUTES] = v }
    suspend fun setDefaultSnoozeMinutes(v: Int) = edit { it[KEY_SNOOZE] = v }
    suspend fun setWeatherEnabled(v: Boolean) = edit { it[KEY_WEATHER] = v }
    suspend fun setOnboardingDone(v: Boolean) = edit { it[KEY_ONBOARDED] = v }
    suspend fun setDefaultSoundUri(v: String?) = edit { if (v == null) it.remove(KEY_SOUND) else it[KEY_SOUND] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val KEY_MINUTES = intPreferencesKey("default_minutes_before")
        val KEY_SNOOZE = intPreferencesKey("default_snooze_minutes")
        val KEY_WEATHER = booleanPreferencesKey("weather_enabled")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarding_done")
        val KEY_SOUND = stringPreferencesKey("default_sound_uri")
    }
}
