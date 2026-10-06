package com.calendaralarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.shared.model.AlarmKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * タイマー (エンジン経由で鳴る) とストップウォッチ (画面内のみ)。
 * タイマーの鳴動はアラームと同じ経路 = 止めるまで鳴る。
 */
@Composable
fun TimerScreen(repository: AlarmRepository) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("タイマー") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("ストップウォッチ") })
        }
        when (tab) {
            0 -> TimerPane(repository)
            else -> StopwatchPane()
        }
    }
}

@Composable
private fun TimerPane(repository: AlarmRepository) {
    val scope = rememberCoroutineScope()
    val pending by repository.pendingFlow().collectAsState(initial = emptyList())
    val timer = pending.firstOrNull { it.kind == AlarmKind.TIMER }

    var minutesInput by remember { mutableStateOf("5") }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(timer != null) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(500)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (timer != null) {
            val remain = (timer.triggerAtMillis - nowMillis).coerceAtLeast(0)
            Text(
                formatRemaining(remain),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(timer.triggerAtMillis)) + " に鳴ります",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = {
                scope.launch { repository.onDismissed(timer.id) }
            }) {
                Text("キャンセル")
            }
        } else {
            Text("タイマー", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = minutesInput,
                onValueChange = { minutesInput = it.filter { c -> c.isDigit() }.take(3) },
                label = { Text("分数") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.6f),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 5, 10, 30, 60).forEach { m ->
                    FilterChip(
                        selected = minutesInput == m.toString(),
                        onClick = { minutesInput = m.toString() },
                        label = { Text("$m") },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val minutes = minutesInput.toLongOrNull() ?: return@Button
                    if (minutes <= 0) return@Button
                    scope.launch {
                        val inst = AlarmExpander.timerInstance(
                            durationMillis = minutes * 60_000L,
                            now = Clock.System.now(),
                        )
                        repository.scheduleAdhoc(inst)
                    }
                },
                modifier = Modifier.height(56.dp),
            ) { Text("開始") }
        }
    }
}

@Composable
private fun StopwatchPane() {
    var running by remember { mutableStateOf(false) }
    var baseMillis by remember { mutableLongStateOf(0L) }
    var accum by remember { mutableLongStateOf(0L) }
    var laps by remember { mutableStateOf(listOf<Long>()) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(running) {
        while (running) {
            nowMillis = System.currentTimeMillis()
            delay(50)
        }
    }
    val elapsed = accum + if (running) nowMillis - baseMillis else 0L

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(formatStopwatch(elapsed), fontSize = 56.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (running) {
                    accum += nowMillis - baseMillis
                    running = false
                } else {
                    baseMillis = System.currentTimeMillis()
                    running = true
                }
            }) {
                Text(if (running) "停止" else "開始")
            }
            OutlinedButton(
                onClick = { laps = laps + elapsed },
                enabled = running,
            ) { Text("ラップ") }
            OutlinedButton(
                onClick = {
                    running = false; accum = 0L; laps = emptyList()
                },
            ) { Text("リセット") }
        }
        Spacer(Modifier.height(16.dp))
        laps.asReversed().take(5).forEachIndexed { i, lap ->
            Text(
                "ラップ ${laps.size - i}: ${formatStopwatch(lap)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

private fun formatStopwatch(ms: Long): String {
    val t = ms / 10
    return "%d:%02d.%02d".format(t / 6000, (t / 100) % 60, t % 100)
}
