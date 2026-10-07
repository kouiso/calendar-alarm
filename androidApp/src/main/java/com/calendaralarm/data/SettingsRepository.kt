package com.calendaralarm.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.InviteFilter
import com.calendaralarm.shared.model.InviteStatus
import com.calendaralarm.shared.model.TitleCodeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** アプリ全体設定。DataStore Preferences で保持する。 */
class SettingsRepository(private val context: Context) {

    /** タイマープリセット (元アプリの定型タイマーに相当)。 */
    data class TimerPreset(val label: String, val minutes: Int)

    data class Settings(
        val defaultMinutesBefore: Int = 0,
        val defaultSnoozeMinutes: Int = 10,
        val weatherEnabled: Boolean = true,
        val onboardingDone: Boolean = false,
        /** 既定の開始時アクション。 */
        val defaultStartAction: EventAction = EventAction.ALARM,
        /** 既定のリマインダーアクション。 */
        val defaultReminderAction: EventAction = EventAction.ALARM,
        /** タイトルコード設定。 */
        val titleCodes: TitleCodeSettings = TitleCodeSettings(),
        /** 招待予定フィルタ。 */
        val inviteFilter: InviteFilter = InviteFilter(),
        /** 予定側リマインダーを鳴動対象にするか。 */
        val importEventReminders: Boolean = false,
        /** 鳴動画面のスヌーズプリセット (分)。 */
        val snoozePresets: List<Int> = listOf(5, 10, 15, 30, 45, 60),
        /** アラーム音量。0以下はシステムの音量に追従、1〜100はアプリ固定音量。 */
        val alarmVolumePercent: Int = 0,
        /** 徐々に音量を上げるか。 */
        val volumeCrescendo: Boolean = false,
        /** 鳴動中にバイブするか。 */
        val vibrateWhileRinging: Boolean = true,
        /** 一括ミュート: ALARM鳴動を全て止める (通知のみは残す)。 */
        val muteAll: Boolean = false,
        /** 既定の鳴動音 URI (カスタムMP3等)。null=システム既定。 */
        val defaultSoundUri: String? = null,
        /** 週番号を予定一覧に表示するか。 */
        val showWeekNumbers: Boolean = false,
        /** 週の開始曜日 (1=月 … 7=日)。 */
        val firstWeekday: Int = 1,
        /** テーマ ID。 */
        val themeId: String = "default",
        /** カスタム背景画像 URI (鳴動画面/テーマ)。 */
        val backgroundImageUri: String? = null,
        /** 鳴動画面に天気を表示するか。 */
        val weatherOnAlarmScreen: Boolean = true,
        /** 予定一覧ヘッダーに現在の天気を出すか。 */
        val weatherHeaderEnabled: Boolean = true,
        /** 停止後に次のアラームを表示するか。 */
        val showNextAlarmAfterDismiss: Boolean = false,
        /** タイマープリセット。 */
        val timerPresets: List<TimerPreset> = DEFAULT_TIMER_PRESETS,
        /** OpenRouter APIキー (AI機能用。ローカル入力)。 */
        val openRouterApiKey: String? = null,
        /** OpenRouter のモデル指定。 */
        val openRouterModel: String = "openai/gpt-4.1-mini",
    )

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            defaultMinutesBefore = p[KEY_MINUTES] ?: 0,
            defaultSnoozeMinutes = p[KEY_SNOOZE] ?: 10,
            weatherEnabled = p[KEY_WEATHER] ?: true,
            onboardingDone = p[KEY_ONBOARDED] ?: false,
            defaultStartAction = p[KEY_DEF_START_ACTION]
                ?.let { runCatching { EventAction.valueOf(it) }.getOrNull() } ?: EventAction.ALARM,
            defaultReminderAction = p[KEY_DEF_REM_ACTION]
                ?.let { runCatching { EventAction.valueOf(it) }.getOrNull() } ?: EventAction.ALARM,
            titleCodes = TitleCodeSettings(
                alwaysCodes = p[KEY_ALWAYS_CODES].toCsvList(),
                neverCodes = p[KEY_NEVER_CODES].toCsvList(),
                applyToStart = (p[KEY_CODE_SCOPE] ?: 0) != 2,
                applyToReminders = (p[KEY_CODE_SCOPE] ?: 0) != 1,
            ),
            inviteFilter = InviteFilter(
                accepted = (p[KEY_INVITE_MASK] ?: 7) and 1 != 0,
                tentative = (p[KEY_INVITE_MASK] ?: 7) and 2 != 0,
                needsAction = (p[KEY_INVITE_MASK] ?: 7) and 4 != 0,
                declined = (p[KEY_INVITE_MASK] ?: 7) and 8 != 0,
            ),
            importEventReminders = p[KEY_IMPORT_REMINDERS] ?: false,
            snoozePresets = (p[KEY_SNOOZE_PRESETS] ?: "5,10,15,30,45,60")
                .toCsvList().mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(10) },
            alarmVolumePercent = p[KEY_ALARM_VOLUME] ?: 0,
            volumeCrescendo = p[KEY_VOL_CRESCENDO] ?: false,
            vibrateWhileRinging = p[KEY_VIBRATE] ?: true,
            muteAll = p[KEY_MUTE_ALL] ?: false,
            defaultSoundUri = p[KEY_SOUND_URI],
            showWeekNumbers = p[KEY_WEEK_NUMBERS] ?: false,
            firstWeekday = p[KEY_FIRST_WEEKDAY] ?: 1,
            themeId = p[KEY_THEME_ID] ?: "default",
            backgroundImageUri = p[KEY_BG_IMAGE_URI],
            weatherOnAlarmScreen = p[KEY_WEATHER_ALARM] ?: true,
            weatherHeaderEnabled = p[KEY_WEATHER_HEADER] ?: true,
            showNextAlarmAfterDismiss = p[KEY_NEXT_ALARM_MSG] ?: false,
            timerPresets = (p[KEY_TIMER_PRESETS]
                ?: DEFAULT_TIMER_PRESETS.joinToString(";") { "${it.label}|${it.minutes}" })
                .split(';').mapNotNull { part ->
                    val (label, min) = part.split('|').let {
                        it.getOrNull(0) to it.getOrNull(1)
                    }
                    label?.let { l -> min?.toIntOrNull()?.let { m -> TimerPreset(l, m) } }
                }.ifEmpty { DEFAULT_TIMER_PRESETS },
            openRouterApiKey = p[KEY_OPENROUTER_KEY]?.ifBlank { null },
            openRouterModel = p[KEY_OPENROUTER_MODEL] ?: "openai/gpt-4.1-mini",
        )
    }

    suspend fun setDefaultMinutesBefore(v: Int) = edit { it[KEY_MINUTES] = v }
    suspend fun setDefaultSnoozeMinutes(v: Int) = edit { it[KEY_SNOOZE] = v }
    suspend fun setWeatherEnabled(v: Boolean) = edit { it[KEY_WEATHER] = v }
    suspend fun setOnboardingDone(v: Boolean) = edit { it[KEY_ONBOARDED] = v }
    suspend fun setDefaultStartAction(v: EventAction) = edit { it[KEY_DEF_START_ACTION] = v.name }
    suspend fun setDefaultReminderAction(v: EventAction) = edit { it[KEY_DEF_REM_ACTION] = v.name }
    suspend fun setTitleCodes(v: TitleCodeSettings) = edit {
        it[KEY_ALWAYS_CODES] = v.alwaysCodes.joinToString(",")
        it[KEY_NEVER_CODES] = v.neverCodes.joinToString(",")
        it[KEY_CODE_SCOPE] = when {
            v.applyToStart && v.applyToReminders -> 0
            v.applyToStart -> 1
            else -> 2
        }
    }
    suspend fun setInviteFilter(v: InviteFilter) = edit {
        it[KEY_INVITE_MASK] =
            (if (v.accepted) 1 else 0) +
                (if (v.tentative) 2 else 0) +
                (if (v.needsAction) 4 else 0) +
                (if (v.declined) 8 else 0)
    }
    suspend fun toggleInviteStatus(status: InviteStatus) = edit { p ->
        val bit = when (status) {
            InviteStatus.ACCEPTED -> 1
            InviteStatus.TENTATIVE -> 2
            InviteStatus.NEEDS_ACTION -> 4
            InviteStatus.DECLINED -> 8
        }
        p[KEY_INVITE_MASK] = (p[KEY_INVITE_MASK] ?: 7) xor bit
    }
    suspend fun setImportEventReminders(v: Boolean) = edit { it[KEY_IMPORT_REMINDERS] = v }
    suspend fun setSnoozePresets(v: List<Int>) = edit { it[KEY_SNOOZE_PRESETS] = v.joinToString(",") }
    suspend fun setAlarmVolumePercent(v: Int) = edit { it[KEY_ALARM_VOLUME] = v }
    suspend fun setVolumeCrescendo(v: Boolean) = edit { it[KEY_VOL_CRESCENDO] = v }
    suspend fun setVibrateWhileRinging(v: Boolean) = edit { it[KEY_VIBRATE] = v }
    suspend fun setMuteAll(v: Boolean) = edit { it[KEY_MUTE_ALL] = v }
    suspend fun setDefaultSoundUri(v: String?) = edit {
        if (v == null) it.remove(KEY_SOUND_URI) else it[KEY_SOUND_URI] = v
    }
    suspend fun setShowWeekNumbers(v: Boolean) = edit { it[KEY_WEEK_NUMBERS] = v }
    suspend fun setFirstWeekday(v: Int) = edit { it[KEY_FIRST_WEEKDAY] = v }
    suspend fun setThemeId(v: String) = edit { it[KEY_THEME_ID] = v }
    suspend fun setBackgroundImageUri(v: String?) = edit {
        if (v == null) it.remove(KEY_BG_IMAGE_URI) else it[KEY_BG_IMAGE_URI] = v
    }
    suspend fun setWeatherOnAlarmScreen(v: Boolean) = edit { it[KEY_WEATHER_ALARM] = v }
    suspend fun setWeatherHeaderEnabled(v: Boolean) = edit { it[KEY_WEATHER_HEADER] = v }
    suspend fun setShowNextAlarmAfterDismiss(v: Boolean) = edit { it[KEY_NEXT_ALARM_MSG] = v }
    suspend fun setTimerPresets(v: List<TimerPreset>) = edit {
        it[KEY_TIMER_PRESETS] = v.joinToString(";") { p -> "${p.label}|${p.minutes}" }
    }
    suspend fun setOpenRouterApiKey(v: String?) = edit {
        if (v.isNullOrBlank()) it.remove(KEY_OPENROUTER_KEY) else it[KEY_OPENROUTER_KEY] = v
    }
    suspend fun setOpenRouterModel(v: String) = edit { it[KEY_OPENROUTER_MODEL] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val KEY_MINUTES = intPreferencesKey("default_minutes_before")
        val KEY_SNOOZE = intPreferencesKey("default_snooze_minutes")
        val KEY_WEATHER = booleanPreferencesKey("weather_enabled")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarding_done")
        val KEY_DEF_START_ACTION = stringPreferencesKey("default_start_action")
        val KEY_DEF_REM_ACTION = stringPreferencesKey("default_reminder_action")
        val KEY_ALWAYS_CODES = stringPreferencesKey("title_always_codes")
        val KEY_NEVER_CODES = stringPreferencesKey("title_never_codes")
        val KEY_CODE_SCOPE = intPreferencesKey("title_code_scope")
        val KEY_INVITE_MASK = intPreferencesKey("invite_mask")
        val KEY_IMPORT_REMINDERS = booleanPreferencesKey("import_event_reminders")
        val KEY_SNOOZE_PRESETS = stringPreferencesKey("snooze_presets")
        val KEY_ALARM_VOLUME = intPreferencesKey("alarm_volume_percent")
        val KEY_VOL_CRESCENDO = booleanPreferencesKey("volume_crescendo")
        val KEY_VIBRATE = booleanPreferencesKey("vibrate_while_ringing")
        val KEY_MUTE_ALL = booleanPreferencesKey("mute_all")
        val KEY_SOUND_URI = stringPreferencesKey("default_sound_uri")
        val KEY_WEEK_NUMBERS = booleanPreferencesKey("show_week_numbers")
        val KEY_FIRST_WEEKDAY = intPreferencesKey("first_weekday")
        val KEY_THEME_ID = stringPreferencesKey("theme_id")
        val KEY_BG_IMAGE_URI = stringPreferencesKey("background_image_uri")
        val KEY_WEATHER_ALARM = booleanPreferencesKey("weather_on_alarm_screen")
        val KEY_WEATHER_HEADER = booleanPreferencesKey("weather_header_enabled")
        val KEY_NEXT_ALARM_MSG = booleanPreferencesKey("show_next_alarm_after_dismiss")
        val KEY_TIMER_PRESETS = stringPreferencesKey("timer_presets")
        val KEY_OPENROUTER_KEY = stringPreferencesKey("openrouter_api_key")
        val KEY_OPENROUTER_MODEL = stringPreferencesKey("openrouter_model")

        /** 元アプリの定型タイマー (ゆで卵/パスタ/紅茶/ピザ/仮眠/集中/筋トレ休憩)。 */
        val DEFAULT_TIMER_PRESETS = listOf(
            TimerPreset("ゆで卵", 5),
            TimerPreset("パスタ", 9),
            TimerPreset("紅茶", 4),
            TimerPreset("ピザ", 12),
            TimerPreset("仮眠", 20),
            TimerPreset("集中", 25),
            TimerPreset("筋トレ休憩", 90 / 60),
        )

        fun String?.toCsvList(): List<String> =
            this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }
}
