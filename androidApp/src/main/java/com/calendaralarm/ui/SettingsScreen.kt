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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("イベント連動の既定", style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.padding(top = 4.dp)) {
                    listOf(0, 5, 10, 15).forEach { m ->
                        FilterChip(
                            selected = defaultMin == m,
                            onClick = { scope.launch { settings.setDefaultMinutesBefore(m) } },
                            label = { Text(if (m == 0) "開始時" else "${m}分前") },
                            modifier = Modifier.padding(end = 6.dp),
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
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    calendars.forEach { cal ->
                        CalendarRow(
                            cal = cal,
                            pref = calPrefs[cal.id],
                            defaultMinutes = prefs?.defaultMinutesBefore ?: 0,
                            onChange = { enabled, minutes, allDay ->
                                scope.launch {
                                    repository.setCalendarPref(cal.id, enabled, minutes, allDayMinutes = allDay)
                                    calPrefs = repository.calendarPrefsFlowList().associateBy { it.calendarId }
                                }
                            },
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "端末に登録されたアカウントのカレンダーは自動で取り込まれます。\nMicrosoft(Outlook/Exchange) は Outlook アプリの「カレンダーと同期」か、端末設定のアカウント追加 (Exchange) で出てきます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_ADD_ACCOUNT)
                                    .putExtra(
                                        Settings.EXTRA_ACCOUNT_TYPES,
                                        arrayOf(
                                            "com.microsoft.exchange",
                                            "com.android.exchange",
                                            "com.exchange",
                                            "com.google",
                                            "com.microsoft.office.outlook.account",
                                        ),
                                    ),
                            )
                        }
                    },
                ) {
                    Text("＋ アカウントを追加してカレンダーを取り込む")
                }
            }
        }

        // ---- 権限の健康状態 ----
        item { SectionHeader("権限・信頼性") }
        item { PermissionHealthCard(context, repository) }

        // ---- 監査ログ ----
        item { SectionHeader("鳴動ログ (直近50件)") }
        if (audit.isEmpty()) {
            item {
                Text(
                    "まだログがありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        audit.forEach { log -> AuditRow(log) }
                    }
                }
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
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
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
    // canScheduleExactAlarms() は API 31+。それ未満は正確アラームが常時許可なので true。
    val exactOk = Build.VERSION.SDK_INT < 31 ||
        (context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: true)
    val fsiOk = if (Build.VERSION.SDK_INT >= 34) {
        context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true
    } else true
    val batteryOk = context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    Card(
        Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
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

/**
 * カレンダー1行。既定は折り畳み (名前・アカウント・鳴動サマリ・スイッチのみ)。
 * タップで鳴動タイミング/終日時刻のチップ群が展開する。
 */
@Composable
private fun CalendarRow(
    cal: CalendarSource,
    pref: CalendarPrefEntity?,
    defaultMinutes: Int,
    onChange: (enabled: Boolean, minutesBefore: Int, allDayMinutes: Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = pref?.enabled ?: true
    val minutes = pref?.minutesBefore ?: defaultMinutes
    val allDay = pref?.allDayMinutes ?: 540

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(10.dp)
                    .background(Color(cal.color), CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(cal.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    buildString {
                        append(cal.accountName)
                        if (enabled) {
                            append("  ·  ")
                            append(if (minutes == 0) "開始時" else "${minutes}分前")
                            append(" / 終日")
                            append(if (allDay < 0) "OFF" else "%d:%02d".format(allDay / 60, allDay % 60))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { onChange(it, minutes, null) },
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "畳む" else "開く",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(enabled && expanded) {
            Column(Modifier.padding(start = 20.dp, top = 6.dp)) {
                Row {
                    listOf(0, 5, 10, 15, 30).forEach { m ->
                        FilterChip(
                            selected = minutes == m,
                            onClick = { onChange(true, m, null) },
                            label = { Text(if (m == 0) "開始時" else "${m}分", maxLines = 1) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "終日",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    listOf(-1 to "OFF", 480 to "8:00", 540 to "9:00", 720 to "12:00").forEach { (v, label) ->
                        FilterChip(
                            selected = allDay == v,
                            onClick = { onChange(true, minutes, v) },
                            label = { Text(label, maxLines = 1) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            }
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
