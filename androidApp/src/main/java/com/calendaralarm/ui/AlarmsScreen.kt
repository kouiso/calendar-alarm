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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.shared.model.StandaloneAlarm
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
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

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(0L) }) {
                Icon(Icons.Default.Add, contentDescription = "アラーム追加")
            }
        },
    ) { padding ->
        if (alarms.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("アラームがありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "右下の + で作成できます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp)) {
                items(alarms, key = { it.id }) { alarm ->
                    AlarmRow(
                        alarm = alarm,
                        nextInstance = pending.firstOrNull { it.standaloneAlarmId == alarm.id },
                        onToggle = { on ->
                            scope.launch {
                                repository.upsertStandaloneAlarm(alarm.copy(enabled = on))
                            }
                        },
                        onDelete = {
                            scope.launch { repository.deleteStandaloneAlarm(alarm.id) }
                        },
                        onClick = { onEdit(alarm.id) },
                    )
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "%d:%02d".format(alarm.hour, alarm.minute),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    repeatLabel(alarm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (alarm.label.isNotBlank()) {
                    Text(alarm.label, style = MaterialTheme.typography.bodyMedium)
                }
                nextInstance?.let {
                    Text(
                        "次: " + SimpleDateFormat("M/d(E) H:mm", Locale.JAPAN).format(Date(it.triggerAtMillis)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (alarm.exceptions.isNotEmpty()) {
                    Text(
                        "休止日: ${alarm.exceptions.size}件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
    if (alarm.daysOfWeek.isEmpty()) {
        "1回のみ"
    } else {
        val order = listOf(
            DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
        )
        "毎週 " + order.filter { it in alarm.daysOfWeek }.joinToString("") { it.jaShort() }
    }

private fun DayOfWeek.jaShort(): String = when (this) {
    DayOfWeek.SUNDAY -> "日"
    DayOfWeek.MONDAY -> "月"
    DayOfWeek.TUESDAY -> "火"
    DayOfWeek.WEDNESDAY -> "水"
    DayOfWeek.THURSDAY -> "木"
    DayOfWeek.FRIDAY -> "金"
    DayOfWeek.SATURDAY -> "土"
}
