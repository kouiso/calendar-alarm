package com.calendaralarm.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.shared.model.StandaloneAlarm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * アラーム編集。時刻・曜日繰り返し・休止日・ラベル・音・スヌーズを一画面で。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    repository: AlarmRepository,
    settings: SettingsRepository,
    alarmId: Long,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val alarms by repository.standaloneAlarmsFlow().collectAsState(initial = emptyList())
    val existing = alarms.firstOrNull { it.id == alarmId }

    var loaded by remember { mutableStateOf(alarmId == 0L) }
    var hour by remember { mutableIntStateOf(7) }
    var minute by remember { mutableIntStateOf(0) }
    var label by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(setOf<DayOfWeek>()) }
    var exceptions by remember { mutableStateOf(setOf<LocalDate>()) }
    var snooze by remember { mutableIntStateOf(10) }
    var soundUri by remember { mutableStateOf<String?>(null) }
    var enabled by remember { mutableStateOf(true) }
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (alarmId == 0L) {
            snooze = settings.flow.first().defaultSnoozeMinutes
        }
    }

    val timeState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)

    LaunchedEffect(existing?.id) {
        if (!loaded && existing != null) {
            hour = existing.hour
            minute = existing.minute
            label = existing.label
            days = existing.daysOfWeek
            exceptions = existing.exceptions
            snooze = existing.snoozeMinutes
            soundUri = existing.soundUri
            enabled = existing.enabled
            // TimePicker は remember の初期値しか見ないため、
            // 既存アラームの読み込み後に明示的に同期する
            timeState.hour = existing.hour
            timeState.minute = existing.minute
            loaded = true
        }
    }

    val soundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            soundUri = it.toString()
        }
    }

    LaunchedEffect(timeState.hour, timeState.minute) {
        hour = timeState.hour
        minute = timeState.minute
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (alarmId == 0L) "アラーム作成" else "アラーム編集") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            TimePicker(state = timeState)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("ラベル (例: 出社)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // 曜日繰り返し
            Text("繰り返し", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val order = listOf(
                    DayOfWeek.SUNDAY to "日", DayOfWeek.MONDAY to "月", DayOfWeek.TUESDAY to "火",
                    DayOfWeek.WEDNESDAY to "水", DayOfWeek.THURSDAY to "木",
                    DayOfWeek.FRIDAY to "金", DayOfWeek.SATURDAY to "土",
                )
                order.forEach { (d, ja) ->
                    FilterChip(
                        selected = d in days,
                        onClick = { days = if (d in days) days - d else days + d },
                        label = { Text(ja) },
                    )
                }
            }
            Text(
                if (days.isEmpty()) "未選択 = 1回だけ鳴ります" else "毎週鳴ります",
                Modifier.fillMaxWidth().padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            // 休止日 (例外)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("鳴らない日 (休止日)", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("追加")
                }
            }
            if (exceptions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    exceptions.sorted().forEach { d ->
                        InputChip(
                            selected = false,
                            onClick = { exceptions -= d },
                            label = { Text("${d.monthNumber}/${d.dayOfMonth}") },
                            trailingIcon = {
                                Icon(Icons.Default.Close, contentDescription = "削除", Modifier.width(16.dp))
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            // スヌーズ
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("スヌーズ", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                listOf(5, 10, 15).forEach { m ->
                    FilterChip(
                        selected = snooze == m,
                        onClick = { snooze = m },
                        label = { Text("${m}分") },
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("アラーム音", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = {
                    soundPicker.launch(arrayOf("audio/*"))
                }) {
                    Text(if (soundUri == null) "端末の標準音" else "選択済みの音")
                }
                if (soundUri != null) {
                    TextButton(onClick = { soundUri = null }) { Text("標準に戻す") }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    scope.launch {
                        repository.upsertStandaloneAlarm(
                            StandaloneAlarm(
                                id = alarmId,
                                enabled = enabled,
                                hour = hour, minute = minute,
                                daysOfWeek = days,
                                label = label.trim(),
                                soundUri = soundUri,
                                snoozeMinutes = snooze,
                                exceptions = exceptions,
                            ),
                        )
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("保存") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val d = Instant.fromEpochMilliseconds(millis)
                            .toLocalDateTime(TimeZone.UTC).date
                        exceptions += d
                    }
                    showDatePicker = false
                }) { Text("追加") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("キャンセル") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
