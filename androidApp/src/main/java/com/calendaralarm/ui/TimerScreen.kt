package com.calendaralarm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.ui.theme.OutfitFontFamily
import com.calendaralarm.shared.model.AlarmKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * タイマー (エンジン経由で鳴る) とストップウォッチ (画面内のみ)。
 * タイマーの鳴動はアラームと同じ経路 = 止めるまで鳴る。
 */
@Composable
fun TimerScreen(repository: AlarmRepository, settings: SettingsRepository) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Text(
            "タイマー",
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 58.dp),
        )
        NightSegment(
            labels = listOf("タイマー", "ストップウォッチ"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp).fillMaxWidth(),
        )
        when (tab) {
            0 -> TimerPane(repository, settings)
            else -> StopwatchPane()
        }
    }
}

/**
 * 時計フェイス風リング。60分の目盛りを描き、中央に大きな数字を置く。
 * 進捗は持たない (鳴動エンジン側は残秒しか知らない) が、視覚的な「時計らしさ」を出す。
 */
@Composable
private fun ClockRing(content: @Composable () -> Unit) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(260.dp)) {
            val r = size.minDimension / 2f
            drawCircle(
                color = ring,
                radius = r - 3.dp.toPx(),
                style = Stroke(width = 2.dp.toPx()),
            )
            for (i in 0 until 60) {
                val major = i % 5 == 0
                val angle = Math.toRadians((i * 6 - 90).toDouble())
                val outer = r - 8.dp.toPx()
                val inner = outer - (if (major) 14.dp else 7.dp).toPx()
                drawLine(
                    color = if (i == 0) accent else ring,
                    start = Offset(
                        center.x + outer * cos(angle).toFloat(),
                        center.y + outer * sin(angle).toFloat(),
                    ),
                    end = Offset(
                        center.x + inner * cos(angle).toFloat(),
                        center.y + inner * sin(angle).toFloat(),
                    ),
                    strokeWidth = (if (major) 3.dp else 1.5.dp).toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        content()
    }
}

@Composable
private fun TimerPane(repository: AlarmRepository, settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val pending by repository.pendingFlow().collectAsState(initial = emptyList())
    val timer = pending.firstOrNull { it.kind == AlarmKind.TIMER }
    val prefs by settings.flow.collectAsState(initial = null)

    var minutesInput by remember { mutableStateOf("5") }
    // プリセット選択時の秒指定 (分未満のプリセットを表すため分数入力と別に持つ)
    var presetSeconds by remember { mutableStateOf<Int?>(null) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showAddPreset by remember { mutableStateOf(false) }
    var deletePreset by remember { mutableStateOf<SettingsRepository.TimerPreset?>(null) }

    // タイマーが無いのに500ms刻みで再コンポーズし続けないようガード
    LaunchedEffect(timer?.id) {
        while (timer != null) {
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
            ClockRing {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        formatRemaining(remain),
                        fontSize = 48.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Light,
                        letterSpacing = (-2).sp,
                    )
                    Text(
                        SimpleDateFormat("H:mm", Locale.getDefault())
                            .format(Date(timer.triggerAtMillis)) + " に鳴ります",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            OutlinedButton(
                onClick = { scope.launch { repository.onDismissed(timer.id) } },
                modifier = Modifier.height(52.dp),
            ) {
                Text("キャンセル")
            }
        } else {
            ClockRing {
                // ダイヤル中央は分数ホイール (Outfit 大数字 + 「分」)。スナップで1-90分。
                MinuteWheel(
                    minutes = presetSeconds?.let { it / 60 } ?: (minutesInput.toIntOrNull() ?: 5),
                    onMinutes = {
                        presetSeconds = null
                        minutesInput = it.toString()
                    },
                )
            }
            Spacer(Modifier.height(28.dp))
            // 定型タイマープリセット (元アプリ: ゆで卵/パスタ等)。タップで分数セット、
            // 長押しで削除、「＋」で現在の分数を名前付き保存。
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                prefs?.timerPresets?.forEach { p ->
                    PresetChip(
                        label = "${p.label} ${p.durationLabel()}",
                        selected = presetSeconds == p.seconds,
                        onClick = { presetSeconds = p.seconds },
                        onLongClick = { deletePreset = p },
                    )
                }
                PresetChip(
                    label = "＋",
                    selected = false,
                    onClick = { showAddPreset = true },
                    dashed = true,
                )
            }
            if (showAddPreset) {
                AddPresetDialog(
                    initialMinutes = minutesInput.toIntOrNull() ?: 5,
                    onDismiss = { showAddPreset = false },
                    onSave = { label, minutes ->
                        scope.launch {
                            val cur = prefs?.timerPresets ?: emptyList()
                            settings.setTimerPresets(
                                cur + SettingsRepository.TimerPreset(label, minutes * 60),
                            )
                        }
                        showAddPreset = false
                    },
                )
            }
            deletePreset?.let { target ->
                AlertDialog(
                    onDismissRequest = { deletePreset = null },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                val cur = prefs?.timerPresets ?: emptyList()
                                settings.setTimerPresets(cur - target)
                            }
                            deletePreset = null
                        }) { Text("削除") }
                    },
                    dismissButton = {
                        TextButton(onClick = { deletePreset = null }) { Text("戻る") }
                    },
                    text = { Text("「${target.label}」を削除しますか？") },
                )
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    val durationMillis = presetSeconds?.let { it * 1_000L }
                        ?: (minutesInput.toLongOrNull() ?: 0L) * 60_000L
                    if (durationMillis <= 0) return@Button
                    scope.launch {
                        val inst = AlarmExpander.timerInstance(
                            durationMillis = durationMillis,
                            now = Clock.System.now(),
                        )
                        repository.scheduleAdhoc(inst)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(32.dp),
            ) { Text("開始", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
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
        ClockRing {
            Text(
                formatStopwatch(elapsed),
                fontSize = 48.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Light,
                letterSpacing = (-2).sp,
            )
        }
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (running) {
                        accum += nowMillis - baseMillis
                        running = false
                    } else {
                        baseMillis = System.currentTimeMillis()
                        running = true
                    }
                },
                modifier = Modifier.height(52.dp),
            ) {
                Text(if (running) "停止" else "開始", fontSize = 16.sp)
            }
            OutlinedButton(
                onClick = { laps = laps + elapsed },
                enabled = running,
                modifier = Modifier.height(52.dp),
            ) { Text("ラップ") }
            OutlinedButton(
                onClick = {
                    running = false; accum = 0L; laps = emptyList()
                },
                modifier = Modifier.height(52.dp),
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

/** プリセット用チップ (長押し対応のため FilterChip ではなく自前)。Night UI: 44h, r14, 選択=accent縁。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    dashed: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accent.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            width = if (dashed || selected) 1.dp else 0.dp,
            color = if (selected) accent
            else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.height(44.dp).combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 14.dp),
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 現在の分数を名前付きプリセットとして保存する小さなダイアログ。 */
@Composable
private fun AddPresetDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf(initialMinutes.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val m = minutes.toIntOrNull() ?: return@TextButton
                    if (m <= 0 || label.isBlank()) return@TextButton
                    onSave(label.trim(), m)
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("戻る") } },
        title = { Text("プリセットを追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(12) },
                    label = { Text("名前") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("分数") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/** ダイヤル中央の分数ホイール。スナップで1-90分、中央の値が選択分。 */
@Composable
private fun MinuteWheel(minutes: Int, onMinutes: (Int) -> Unit) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = (minutes - 1).coerceIn(0, 89),
    )
    // 中央にあるアイテムを選択分として確定する
    val centerItem by androidx.compose.runtime.derivedStateOf {
        val info = listState.layoutInfo
        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
        info.visibleItemsInfo.minByOrNull {
            kotlin.math.abs(it.offset + it.size / 2 - center)
        }?.index?.plus(1)
    }
    LaunchedEffect(centerItem) {
        centerItem?.let { if (it != minutes) onMinutes(it) }
    }
    androidx.compose.foundation.lazy.LazyColumn(
        state = listState,
        flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(listState),
        modifier = Modifier.height(120.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(90) { i ->
            val sel = i + 1 == (centerItem ?: minutes)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${i + 1}",
                    fontSize = if (sel) 48.sp else 24.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = if (sel) FontWeight.Light else FontWeight.ExtraLight,
                    color = if (sel) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sel) {
                    Text(
                        "分",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
            }
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
