package com.calendaralarm.ui

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.db.CalendarPrefEntity
import com.calendaralarm.data.db.AuditLogEntity
import com.calendaralarm.shared.model.CalendarSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 設定。鳴動の既定値、カレンダー選択、権限の健康状態、監査ログ。
 * 「なぜ鳴らなかったか」をここで全部追跡できる設計。
 */
@Composable
fun SettingsScreen(
    repository: AlarmRepository,
    settings: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs by settings.flow.collectAsState(initial = null)

    var calendars by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<CalendarSource>>(emptyList())
    }
    var calPrefs by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Map<String, CalendarPrefEntity>>(emptyMap())
    }
    val audit by repository.auditFlow(50).collectAsState(initial = emptyList())

    androidx.compose.runtime.LaunchedEffect(Unit) {
        calendars = repository.calendarSources()
        calPrefs = repository.calendarPrefsFlowList().associateBy { it.calendarId }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        // ---- 鳴動の既定値 ----
        item { SectionHeader("鳴動") }
        item {
            val defaultMin = prefs?.defaultMinutesBefore ?: 0
            SettingRow("イベント連動の既定") {
                Row {
                    listOf(0, 5, 10, 15).forEach { m ->
                        FilterChip(
                            selected = defaultMin == m,
                            onClick = { scope.launch { settings.setDefaultMinutesBefore(m) } },
                            label = { Text(if (m == 0) "開始時" else "${m}分前") },
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
        item {
            val snooze = prefs?.defaultSnoozeMinutes ?: 10
            SettingRow("スヌーズの既定") {
                Row {
                    listOf(5, 10, 15).forEach { m ->
                        FilterChip(
                            selected = snooze == m,
                            onClick = { scope.launch { settings.setDefaultSnoozeMinutes(m) } },
                            label = { Text("${m}分") },
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
        item {
            SettingRow("イベント場所の天気予報") {
                Switch(
                    checked = prefs?.weatherEnabled ?: true,
                    onCheckedChange = { scope.launch { settings.setWeatherEnabled(it) } },
                )
            }
        }

        // ---- カレンダー ----
        item { SectionHeader("カレンダー") }
        items(calendars, key = { it.id }) { cal ->
            val pref = calPrefs[cal.id]
            val enabled = pref?.enabled ?: true
            Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cal.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            cal.accountName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            val min = pref?.minutesBefore ?: (prefs?.defaultMinutesBefore ?: 0)
                            scope.launch {
                                repository.setCalendarPref(cal.id, on, min)
                                calPrefs = repository.calendarPrefsFlowList().associateBy { it.calendarId }
                            }
                        },
                    )
                }
                if (enabled) {
                    Row(Modifier.padding(start = 0.dp, top = 2.dp)) {
                        listOf(0, 5, 10, 15, 30).forEach { m ->
                            FilterChip(
                                selected = (pref?.minutesBefore ?: (prefs?.defaultMinutesBefore ?: 0)) == m,
                                onClick = {
                                    scope.launch {
                                        repository.setCalendarPref(cal.id, true, m)
                                        calPrefs = repository.calendarPrefsFlowList().associateBy { it.calendarId }
                                    }
                                },
                                label = { Text(if (m == 0) "開始時" else "${m}分前") },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
        }

        // ---- 権限の健康状態 ----
        item { SectionHeader("権限・信頼性") }
        item { PermissionHealthCard(context, repository) }

        // ---- 監査ログ ----
        item { SectionHeader("鳴動ログ (直近50件)") }
        items(audit, key = { it.id }) { log ->
            AuditRow(log)
        }
        if (audit.isEmpty()) {
            item {
                Text(
                    "まだログがありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        content()
    }
}

/** 権限状態を一覧し、足りなければ設定画面へ誘導する。 */
@Composable
private fun PermissionHealthCard(context: Context, repository: AlarmRepository) {
    val scope = rememberCoroutineScope()
    val activity = context as? Activity

    val notifOk = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    val exactOk = context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: true
    val fsiOk = if (Build.VERSION.SDK_INT >= 34) {
        context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true
    } else true
    val batteryOk = context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            HealthRow("通知の許可", notifOk) {
                activity?.let {
                    val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    it.startActivity(i)
                }
            }
            HealthRow("正確なアラーム", exactOk) {
                activity?.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    },
                )
            }
            HealthRow("フルスクリーン通知", fsiOk) {
                if (Build.VERSION.SDK_INT >= 34) {
                    activity?.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = Uri.parse("package:${context.packageName}")
                        },
                    )
                }
            }
            HealthRow("電池最適化から除外", batteryOk) {
                activity?.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "メーカー独自の節電機能 (dontkillmyapp 系) がある端末では、メーカー設定でこのアプリを「保護」「起動許可」にしてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "手動で今すぐ再同期",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clickable {
                        scope.launch { repository.resync("manual") }
                    },
            )
        }
    }
}

@Composable
private fun HealthRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (ok) {
            Text("OK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        } else {
            Text(
                "設定を開く",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onFix),
            )
        }
    }
}

@Composable
private fun AuditRow(log: AuditLogEntity) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            SimpleDateFormat("M/d HH:mm:ss", Locale.getDefault()).format(Date(log.atMillis)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            log.action,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            log.detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
