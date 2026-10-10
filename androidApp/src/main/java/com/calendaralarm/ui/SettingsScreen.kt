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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.data.db.CalendarPrefEntity
import com.calendaralarm.data.db.AuditLogEntity
import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.model.CalendarSource
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.EventType
import com.calendaralarm.shared.model.InviteStatus
import com.calendaralarm.shared.model.TitleCodeSettings
import com.calendaralarm.ui.theme.AppPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 設定。既定値・カレンダー選択・権限・監査ログ。
 * 説明文を置かずアイコン+値で分かる設計。読ませるのはデータだけ。
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
        // ---- 既定値: 🔔通知タイミング / 😴スヌーズ / ☁天気 ----
        item { Spacer(Modifier.height(8.dp)) }
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    // 通知タイミング
                    IconSettingRow(Icons.Default.Notifications) {
                        listOf(0, 5, 10, 15).forEach { m ->
                            FilterChip(
                                selected = (prefs?.defaultMinutesBefore ?: 0) == m,
                                onClick = { scope.launch { settings.setDefaultMinutesBefore(m) } },
                                label = { Text(if (m == 0) "開始時" else "${m}分", maxLines = 1) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                    // スヌーズ
                    IconSettingRow(Icons.Default.Snooze) {
                        listOf(5, 10, 15).forEach { m ->
                            FilterChip(
                                selected = (prefs?.defaultSnoozeMinutes ?: 10) == m,
                                onClick = { scope.launch { settings.setDefaultSnoozeMinutes(m) } },
                                label = { Text("${m}分", maxLines = 1) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                    // 天気
                    IconSettingRow(Icons.Default.WbSunny) {
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = prefs?.weatherEnabled ?: true,
                            onCheckedChange = { scope.launch { settings.setWeatherEnabled(it) } },
                        )
                    }
                    // 天気の地点 (空ならヘッダー/鳴動の天気を出さない)
                    IconSettingRow(Icons.Default.WbSunny) {
                        var loc by remember(prefs?.weatherLocation) {
                            mutableStateOf(prefs?.weatherLocation.orEmpty())
                        }
                        OutlinedTextField(
                            value = loc,
                            onValueChange = { loc = it },
                            placeholder = { Text("地点 (例: 東京)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { scope.launch { settings.setWeatherLocation(loc) } },
                        ) { Text("保存") }
                    }
                    // ヘッダー予報
                    IconSettingRow(Icons.Default.WbSunny) {
                        Text("ヘッダー", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = prefs?.weatherHeaderEnabled ?: true,
                            onCheckedChange = { scope.launch { settings.setWeatherHeaderEnabled(it) } },
                        )
                    }
                    // 鳴動画面の天気
                    IconSettingRow(Icons.Default.WbSunny) {
                        Text("鳴動画面", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = prefs?.weatherOnAlarmScreen ?: true,
                            onCheckedChange = { scope.launch { settings.setWeatherOnAlarmScreen(it) } },
                        )
                    }
                }
            }
        }

        // ---- 外観: テーマ10種 + カスタム背景 ----
        item { Spacer(Modifier.height(12.dp)) }
        item {
            prefs?.let { p ->
                AppearanceCard(p, settings, scope, context)
            }
        }

        // ---- 通知アラーム: 他アプリ通知→アラーム (権限誘導+ルール) ----
        item { Spacer(Modifier.height(12.dp)) }
        item {
            prefs?.let { p -> NotificationRulesCard(settings, p) }
        }

        // ---- AI: OpenRouter キー (メール→予定・通知ルール生成に使用) ----
        item { Spacer(Modifier.height(12.dp)) }
        item {
            prefs?.let { p ->
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        IconSettingRow(Icons.Default.Email) {
                            var key by remember(p.openRouterApiKey) {
                                mutableStateOf(p.openRouterApiKey.orEmpty())
                            }
                            OutlinedTextField(
                                value = key,
                                onValueChange = { key = it },
                                placeholder = { Text("OpenRouter キー") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                scope.launch { settings.setOpenRouterApiKey(key.ifBlank { null }) }
                            }) { Text("保存") }
                        }
                        IconSettingRow(Icons.Default.Email) {
                            var model by remember(p.openRouterModel) {
                                mutableStateOf(p.openRouterModel)
                            }
                            OutlinedTextField(
                                value = model,
                                onValueChange = { model = it },
                                placeholder = { Text("モデル") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                scope.launch { settings.setOpenRouterModel(model) }
                            }) { Text("保存") }
                        }
                    }
                }
            }
        }

        // ---- カレンダー ----
        item { Spacer(Modifier.height(16.dp)) }
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
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
                            onChange = { enabled, minutes, allDay, startA, reminderA ->
                                scope.launch {
                                    repository.setCalendarPref(
                                        cal.id, enabled, minutes,
                                        allDayMinutes = allDay,
                                        startAction = startA,
                                        reminderAction = reminderA,
                                    )
                                    calPrefs = repository.calendarPrefsFlowList().associateBy { it.calendarId }
                                }
                            },
                        )
                    }
                }
            }
        }
        // ---- 予定ルール: 既定アクション / タイトルコード / 招待フィルタ / リマインダー取込 ----
        item { Spacer(Modifier.height(12.dp)) }
        item {
            prefs?.let { p ->
                EventRulesCard(p, settings, repository, scope)
            }
        }

        // ---- 鳴動: 音量/クレッシェンド/バイブ/一括ミュート ----
        item { Spacer(Modifier.height(12.dp)) }
        item {
            prefs?.let { p ->
                RingingCard(p, settings, repository, scope)
            }
        }

        item {
            var showAccountHelp by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
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
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = "アカウントを追加",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = { showAccountHelp = true }) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "取り込みの説明",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { scope.launch { repository.resync("manual") } }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "今すぐ再同期",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showAccountHelp) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showAccountHelp = false },
                    confirmButton = {
                        TextButton(onClick = { showAccountHelp = false }) { Text("閉じる") }
                    },
                    text = {
                        Text(
                            "端末に登録されたアカウントのカレンダーは自動で取り込まれます。\n\n" +
                                "Microsoft(Outlook/Exchange) は Outlook アプリの「カレンダーと同期」をオンにするか、" +
                                "端末設定で Exchange アカウントを追加すると出てきます。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                )
            }
        }

        // ---- 権限 ----
        item { Spacer(Modifier.height(16.dp)) }
        item { PermissionHealthCard(context, repository) }

        // ---- 監査ログ ----
        item { Spacer(Modifier.height(16.dp)) }
        item {
            var logExpanded by remember { mutableStateOf(false) }
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { logExpanded = !logExpanded }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.List,
                            contentDescription = "鳴動ログ",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (audit.isEmpty()) "—" else "${audit.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (logExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (logExpanded) "畳む" else "開く",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (logExpanded) {
                        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp)) {
                            audit.forEach { log -> AuditRow(log) }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

/** 先頭に小さなアイコン、その右に操作を並べる行。ラベル文は置かない。 */
@Composable
// 設定画面内で共有するアイコン付き行 (通知ルールカードからも使う)
internal fun IconSettingRow(icon: ImageVector, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/** 権限状態。既定はアイコン+結果だけの1行 — 欠落がある時だけ自動展開して修復導線を出す。 */
@Composable
private fun PermissionHealthCard(context: Context, repository: AlarmRepository) {
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
    val ngCount = listOf(notifOk, exactOk, fsiOk, batteryOk).count { !it }
    var expanded by remember { mutableStateOf(ngCount > 0) }

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.VerifiedUser,
                    contentDescription = "権限",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (ngCount == 0) "OK" else "要対応 ${ngCount}",
                    color = if (ngCount == 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "畳む" else "開く",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                    HealthRow("通知", notifOk) {
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
                    HealthRow("フルスクリーン", fsiOk) {
                        if (Build.VERSION.SDK_INT >= 34) {
                            activity?.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                },
                            )
                        }
                    }
                    HealthRow("電池最適化", batteryOk) {
                        activity?.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            },
                        )
                    }
                    if (!batteryOk) {
                        Text(
                            "メーカー独自の節電機能がある端末では、メーカー設定でもこのアプリを「保護」にしてください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // 鳴らない時の最終確認: 実際に10秒後に鳴らして経路全体を検証する
                    val testScope = rememberCoroutineScope()
                    TextButton(
                        onClick = {
                            testScope.launch {
                                repository.scheduleAdhoc(
                                    AlarmExpander.timerInstance(
                                        durationMillis = 10_000L,
                                        now = Clock.System.now(),
                                    ).copy(title = "テスト鳴動"),
                                )
                            }
                        },
                    ) {
                        Text("10秒後に鳴動テスト", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
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
 * カレンダー1行。既定は折り畳み (色・名前・サマリ・スイッチのみ)。
 * タップで鳴動タイミング/終日時刻のチップ群が展開する。
 */
@Composable
private fun CalendarRow(
    cal: CalendarSource,
    pref: CalendarPrefEntity?,
    defaultMinutes: Int,
    onChange: (enabled: Boolean, minutesBefore: Int, allDayMinutes: Int?,
               startAction: EventAction?, reminderAction: EventAction?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = pref?.enabled ?: true
    val minutes = pref?.minutesBefore ?: defaultMinutes
    val allDay = pref?.allDayMinutes ?: 540
    val startAction = pref?.startAction.toActionOr(EventAction.ALARM)
    val reminderAction = pref?.reminderAction.toActionOr(EventAction.ALARM)

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
                            append(if (minutes == 0) "開始時" else "${minutes}分")
                            append(" · 終日")
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
                onCheckedChange = { onChange(it, minutes, null, null, null) },
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
                            onClick = { onChange(true, m, null, null, null) },
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
                            onClick = { onChange(true, minutes, v, null, null) },
                            label = { Text(label, maxLines = 1) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                // 開始時 / リマインダーそれぞれの既定アクション
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "開始",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    listOf(
                        EventAction.ALARM to "アラーム",
                        EventAction.NOTIFY to "通知",
                        EventAction.MUTE to "OFF",
                    ).forEach { (a, label) ->
                        FilterChip(
                            selected = startAction == a,
                            onClick = { onChange(true, minutes, null, a, null) },
                            label = { Text(label, maxLines = 1) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "通知分",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    listOf(
                        EventAction.ALARM to "アラーム",
                        EventAction.NOTIFY to "通知",
                        EventAction.MUTE to "OFF",
                    ).forEach { (a, label) ->
                        FilterChip(
                            selected = reminderAction == a,
                            onClick = { onChange(true, minutes, null, null, a) },
                            label = { Text(label, maxLines = 1) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun String?.toActionOr(fallback: EventAction): EventAction =
    this?.let { runCatching { EventAction.valueOf(it) }.getOrNull() } ?: fallback

/**
 * 予定ルールカード: 既定の開始/リマインダーアクション、タイトルコード、
 * 招待フィルタ、予定側リマインダー取込をまとめる。
 */
@Composable
private fun EventRulesCard(
    prefs: SettingsRepository.Settings,
    settings: SettingsRepository,
    repository: AlarmRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var showCodes by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            // 既定アクション (開始時 / 通知分)
            IconSettingRow(Icons.Default.Alarm) {
                listOf(
                    EventAction.ALARM to "アラーム",
                    EventAction.NOTIFY to "通知",
                    EventAction.MUTE to "OFF",
                ).forEach { (a, label) ->
                    FilterChip(
                        selected = prefs.defaultStartAction == a,
                        onClick = { scope.launch { settings.setDefaultStartAction(a); repository.resync("settings") } },
                        label = { Text(label, maxLines = 1) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            IconSettingRow(Icons.Default.Notifications) {
                listOf(
                    EventAction.ALARM to "アラーム",
                    EventAction.NOTIFY to "通知",
                    EventAction.MUTE to "OFF",
                ).forEach { (a, label) ->
                    FilterChip(
                        selected = prefs.defaultReminderAction == a,
                        onClick = { scope.launch { settings.setDefaultReminderAction(a); repository.resync("settings") } },
                        label = { Text(label, maxLines = 1) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            // タイトルコード
            IconSettingRow(Icons.Default.Label) {
                val tc = prefs.titleCodes
                Text(
                    if (tc.alwaysCodes.isEmpty() && tc.neverCodes.isEmpty()) "—"
                    else "○${tc.alwaysCodes.size} ×${tc.neverCodes.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showCodes = true }) { Text("編集") }
            }
            // 招待予定フィルタ
            IconSettingRow(Icons.Default.FilterList) {
                listOf(
                    InviteStatus.ACCEPTED to "出席",
                    InviteStatus.TENTATIVE to "未定",
                    InviteStatus.NEEDS_ACTION to "未回答",
                    InviteStatus.DECLINED to "辞退",
                ).forEach { (s, label) ->
                    FilterChip(
                        selected = prefs.inviteFilter.allows(s),
                        onClick = { scope.launch { settings.toggleInviteStatus(s); repository.resync("settings") } },
                        label = { Text(label, maxLines = 1) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            // イベント種別フィルタ (誕生日/不在/勤務場所/タスク/予定)
            IconSettingRow(Icons.Default.Notifications) {
                listOf(
                    EventType.BIRTHDAY to "誕生日",
                    EventType.ABSENCE to "不在",
                    EventType.WORKPLACE to "勤務場所",
                    EventType.TASK to "タスク",
                    EventType.EVENT to "予定",
                ).forEach { (t, label) ->
                    FilterChip(
                        selected = prefs.eventTypeFilter.allows(t),
                        onClick = { scope.launch { settings.toggleEventType(t); repository.resync("settings") } },
                        label = { Text(label, maxLines = 1) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            // Googleカレンダー連携 (eventType を API の正本から取得)
            val googleLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { res ->
                runCatching {
                    com.google.android.gms.auth.api.signin.GoogleSignIn
                        .getSignedInAccountFromIntent(res.data).result?.email
                }.getOrNull()?.let { email ->
                    scope.launch {
                        settings.setGoogleAccount(email)
                        repository.resync("google-link")
                    }
                }
            }
            IconSettingRow(Icons.Default.AccountCircle) {
                val localContext = LocalContext.current
                Column(Modifier.weight(1f)) {
                    Text(
                        prefs.googleAccountEmail
                            ?.let { "連携中: $it" }
                            ?: "未連携 (種別はタイトル推測)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (prefs.googleAccountEmail != null) {
                    TextButton(onClick = {
                        scope.launch { settings.setGoogleAccount(null); repository.resync("google-unlink") }
                    }) { Text("解除") }
                } else {
                    TextButton(onClick = {
                        googleLauncher.launch(
                            com.calendaralarm.data.calendar.GoogleCalendarTypes
                                .signInClient(localContext).signInIntent
                        )
                    }) { Text("連携") }
                }
            }
            // 予定側リマインダーの取込 (既定ON: 元カレンダー通知の「何分前」で鳴らす)
            IconSettingRow(Icons.Default.Email) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "カレンダーの通知設定を使う",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "開始時刻の鳴動に加えて、予定に設定された「○分前」通知の時刻でも鳴らします。" +
                            "端末から実値を取得できない既定通知は設定の既定値（${prefs.defaultMinutesBefore}分前）として扱います",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = prefs.importEventReminders,
                    onCheckedChange = {
                        scope.launch { settings.setImportEventReminders(it); repository.resync("settings") }
                    },
                )
            }
        }
    }
    if (showCodes) {
        TitleCodeDialog(
            initial = prefs.titleCodes,
            onDismiss = { showCodes = false },
            onSave = { scope.launch { settings.setTitleCodes(it); repository.resync("settings") }; showCodes = false },
        )
    }
}

/** タイトルコード編集: 「必ず鳴らす」「鳴らさない」コードリスト + 適用範囲。 */
@Composable
private fun TitleCodeDialog(
    initial: TitleCodeSettings,
    onDismiss: () -> Unit,
    onSave: (TitleCodeSettings) -> Unit,
) {
    var always by remember { mutableStateOf(initial.alwaysCodes) }
    var never by remember { mutableStateOf(initial.neverCodes) }
    var scope by remember { mutableStateOf(initial.scopeValue()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    TitleCodeSettings(
                        alwaysCodes = always,
                        neverCodes = never,
                        applyToStart = scope != 2,
                        applyToReminders = scope != 1,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
        title = { Text("タイトルコード") },
        text = {
            Column {
                CodeEditor(
                    label = "必ず鳴らすコード",
                    codes = always,
                    onChange = { always = it },
                )
                Spacer(Modifier.height(12.dp))
                CodeEditor(
                    label = "鳴らさないコード",
                    codes = never,
                    onChange = { never = it },
                )
                Spacer(Modifier.height(12.dp))
                Text("適用範囲", style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    listOf("両方", "開始のみ", "通知分のみ").forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = scope == i,
                            onClick = { scope = i },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                        ) { Text(label) }
                    }
                }
            }
        },
    )
}

private fun TitleCodeSettings.scopeValue(): Int = when {
    applyToStart && applyToReminders -> 0
    applyToStart -> 1
    else -> 2
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodeEditor(
    label: String,
    codes: List<String>,
    onChange: (List<String>) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    Text(label, style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.take(20) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = {
                val v = input.trim()
                if (v.isNotEmpty() && v !in codes && codes.size < 15) {
                    onChange(codes + v)
                    input = ""
                }
            },
        ) { Text("追加") }
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        codes.forEach { code ->
            FilterChip(
                selected = true,
                onClick = { onChange(codes - code) },
                label = { Text(code) },
                modifier = Modifier.padding(end = 4.dp),
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

// ---- 鳴動設定: 元アプリの音量系 (独立音量・徐々に音量UP・バイブ) + 一括ミュート ----
@Composable
private fun RingingCard(
    p: SettingsRepository.Settings,
    settings: SettingsRepository,
    repository: AlarmRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            // アラーム音量 (0 = 端末のアラーム音量に従う)
            IconSettingRow(Icons.Default.VolumeUp) {
                Text(
                    if (p.alarmVolumePercent <= 0) "音量: 端末設定"
                    else "音量: ${p.alarmVolumePercent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = p.alarmVolumePercent.coerceIn(0, 100) / 100f,
                onValueChange = { v ->
                    scope.launch { settings.setAlarmVolumePercent((v * 100).toInt()) }
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            // スヌーズプリセット (鳴動画面の +N分 ボタンに出る一覧)
            IconSettingRow(Icons.Default.Snooze) {
                listOf(1, 5, 10, 15, 20, 30, 45, 60, 90).forEach { m ->
                    val on = m in p.snoozePresets
                    FilterChip(
                        selected = on,
                        onClick = {
                            val next = if (on) p.snoozePresets - m else (p.snoozePresets + m).sorted()
                            scope.launch { settings.setSnoozePresets(next) }
                        },
                        label = { Text("${m}分", maxLines = 1) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            IconSettingRow(Icons.Default.TrendingUp) {
                Text(
                    "音量を徐々に上げる",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = p.volumeCrescendo,
                    onCheckedChange = { scope.launch { settings.setVolumeCrescendo(it) } },
                )
            }
            IconSettingRow(Icons.Default.Vibration) {
                Text(
                    "バイブレーション",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = p.vibrateWhileRinging,
                    onCheckedChange = { scope.launch { settings.setVibrateWhileRinging(it) } },
                )
            }
            // 全アラーム一括ミュート (通知・タイマーは止めない)
            IconSettingRow(Icons.Default.VolumeOff) {
                Text(
                    "すべてのアラームをミュート",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = p.muteAll,
                    onCheckedChange = { scope.launch { settings.setMuteAll(it); repository.resync("settings") } },
                )
            }
        }
    }
}

/** テーマ10種 + カスタム背景画像。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceCard(
    p: SettingsRepository.Settings,
    settings: SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
) {
    // 画像はアプリ領域にコピーして file:// で保持する (URI権限の寿命問題を避ける)
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val dest = java.io.File(context.filesDir, "custom_background.jpg")
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                            true
                        } ?: false
                    }.getOrDefault(false)
                }
                if (ok) settings.setBackgroundImageUri("file://${dest.absolutePath}")
            }
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            // テーマスウォッチ (10種): 各パレットの primary を丸で表示
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), maxItemsInEachRow = 5) {
                AppPalette.entries.forEach { palette ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { scope.launch { settings.setThemeId(palette.id) } }
                            .padding(6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .background(
                                    color = palette.light.primary,
                                    shape = CircleShape,
                                )
                                .then(
                                    if (p.themeId == palette.id || (p.themeId == "default" && palette == AppPalette.INDIGO)) {
                                        Modifier.background(
                                            Color.Transparent,
                                            CircleShape,
                                        )
                                    } else Modifier,
                                ),
                        ) {
                            if (p.themeId == palette.id || (p.themeId == "default" && palette == AppPalette.INDIGO)) {
                                Icon(
                                    Icons.Default.VerifiedUser,
                                    contentDescription = "選択中",
                                    tint = Color.White,
                                    modifier = Modifier.align(Alignment.Center).size(16.dp),
                                )
                            }
                        }
                        Text(
                            palette.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // カスタム背景
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (p.backgroundImageUri != null) "背景: 設定済み" else "背景画像",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }) { Text("選ぶ") }
                if (p.backgroundImageUri != null) {
                    TextButton(onClick = {
                        scope.launch { settings.setBackgroundImageUri(null) }
                    }) { Text("削除") }
                }
            }
        }
    }
}
