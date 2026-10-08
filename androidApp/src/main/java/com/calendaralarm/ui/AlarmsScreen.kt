package com.calendaralarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.shared.model.RepeatMode
import com.calendaralarm.shared.model.StandaloneAlarm
import com.calendaralarm.ui.theme.OutfitFontFamily
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 単発・曜日繰り返しアラーム一覧。例外日付きの「絶対起きる」系。
 */
@Composable
fun AlarmsScreen(
    repository: AlarmRepository,
    onEdit: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val alarms by repository.standaloneAlarmsFlow().collectAsState(initial = emptyList())
    val pending by repository.pendingFlow().collectAsState(initial = emptyList())

    Scaffold { padding ->
        Column(Modifier.padding(padding)) {
            // Night UI: H1 + 44dp アクセント FAB (右上)
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 58.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("アラーム", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.Surface(
                    onClick = { onEdit(0L) },
                    modifier = Modifier.size(44.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "アラーム追加",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            // 次に鳴るアラームのバナー (accent-soft)
            pending.firstOrNull { it.standaloneAlarmId != null }?.let { next ->
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Text(
                        "次のアラーム " + SimpleDateFormat("M/d(E) H:mm", Locale.JAPAN)
                            .format(Date(next.triggerAtMillis)) + " " + next.title,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        maxLines = 1,
                    )
                }
            }
        if (alarms.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AlarmOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("アラームなし", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
                items(alarms, key = { it.id }) { alarm ->
                    var confirmDelete by remember { mutableStateOf(false) }
                    if (confirmDelete) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { confirmDelete = false },
                            title = { Text("アラームを削除") },
                            text = {
                                Text("%d:%02d %s を削除?".format(alarm.hour, alarm.minute, alarm.label))
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        confirmDelete = false
                                        scope.launch { repository.deleteStandaloneAlarm(alarm.id) }
                                    },
                                ) { Text("削除") }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = { confirmDelete = false },
                                ) { Text("キャンセル") }
                            },
                        )
                    }
                    AlarmRow(
                        alarm = alarm,
                        nextInstance = pending.firstOrNull { it.standaloneAlarmId == alarm.id },
                        onToggle = { on ->
                            scope.launch {
                                repository.upsertStandaloneAlarm(alarm.copy(enabled = on))
                            }
                        },
                        onDelete = { confirmDelete = true },
                        onClick = { onEdit(alarm.id) },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun AlarmRow(
    alarm: StandaloneAlarm,
    nextInstance: com.calendaralarm.shared.model.AlarmInstance?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "%d:%02d".format(alarm.hour, alarm.minute),
                    fontSize = 44.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = if (alarm.enabled) FontWeight.Light else FontWeight.ExtraLight,
                    letterSpacing = (-1).sp,
                    color = if (alarm.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    repeatLabel(alarm),
                    style = MaterialTheme.typography.bodySmall,
                    // Night UI: 有効時は繰り返しラベルをアクセント色に
                    color = if (alarm.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (alarm.label.isNotBlank()) {
                    Text(alarm.label, style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (alarm.exceptions.isNotEmpty()) {
                        // 「休止 N」はアウトラインの小ピル
                        androidx.compose.material3.Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, MaterialTheme.colorScheme.outline,
                            ),
                        ) {
                            Text(
                                "休止 ${alarm.exceptions.size}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.padding(start = 8.dp))
                    }
                    nextInstance?.let {
                        Text(
                            "次 " + SimpleDateFormat("H:mm", Locale.JAPAN)
                                .format(Date(it.triggerAtMillis)),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "削除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun repeatLabel(alarm: StandaloneAlarm): String =
    when (alarm.effectiveRepeatMode()) {
        RepeatMode.ONCE -> "1回のみ"
        RepeatMode.MONTHLY -> {
            val day = alarm.repeatAnchorMillis?.let {
                Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).dayOfMonth
            }
            if (day != null) "毎月${day}日" else "毎月"
        }
        RepeatMode.INTERVAL_DAYS -> "${alarm.repeatInterval}日ごと"
        RepeatMode.INTERVAL_WEEKS -> "${alarm.repeatInterval}週ごと"
        RepeatMode.INTERVAL_MONTHS -> "${alarm.repeatInterval}ヶ月ごと"
        RepeatMode.WEEKLY -> {
            // 曜日空のWEEKLYはONCEとして鳴る
            if (alarm.daysOfWeek.isEmpty()) {
                "1回のみ"
            } else {
                val order = listOf(
                    DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
                )
                "毎週 " + order.filter { it in alarm.daysOfWeek }.joinToString("") { it.jaShort() }
            }
        }
    }

