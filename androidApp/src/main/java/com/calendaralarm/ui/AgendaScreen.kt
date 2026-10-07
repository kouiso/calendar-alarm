package com.calendaralarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calendaralarm.CalendarAlarmApp
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.sync.SyncWorker
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.DailyForecast
import com.calendaralarm.shared.weather.WeatherApi
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val tz = TimeZone.currentSystemDefault()

/**
 * 予定アジェンダ。カレンダーのイベントを日付ごとに並べ、
 * 行ごとに鳴動ON/OFFと事前分数を直接いじれるのが本アプリの主操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(repository: AlarmRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = context.applicationContext as CalendarAlarmApp

    var items by remember { mutableStateOf<List<AlarmRepository.AgendaItem>?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<AlarmRepository.AgendaItem?>(null) }
    val pending by repository.pendingFlow().collectAsState(initial = emptyList())
    val settings by app.container.settings.flow.collectAsState(initial = null)

    LaunchedEffect(refreshKey) {
        items = repository.upcomingEvents()
    }

    val nextAlarm = pending.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        // 次のアラーム帯: いつ鳴るか常時見せるのが信頼感の肝
        if (nextAlarm != null) {
            NextAlarmBanner(nextAlarm.title, nextAlarm.triggerAtMillis)
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = {
                SyncWorker.enqueueNow(context)
                refreshKey++
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "同期")
            }
        }

        val list = items
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("予定なし", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> AgendaList(
                items = list,
                onSelect = { selected = it },
            )
        }
    }

    selected?.let { item ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            EventDetailSheet(
                item = item,
                weatherEnabled = settings?.weatherEnabled ?: true,
                onOverride = { muted, minutes, extraOffsets ->
                    scope.launch {
                        repository.setEventOverride(item.event.instanceKey, muted, minutes, extraOffsets)
                        selected = null
                        refreshKey++
                    }
                },
                onClearOverride = {
                    scope.launch {
                        repository.removeEventOverride(item.event.instanceKey)
                        selected = null
                        refreshKey++
                    }
                },
                weatherLoader = { loc ->
                    runCatching { app.container.weather.forecastForLocation(loc) }.getOrNull()
                },
            )
        }
    }
}

@Composable
private fun NextAlarmBanner(title: String, triggerAtMillis: Long) {
    val time = SimpleDateFormat("M/d(E) H:mm", Locale.JAPAN).format(Date(triggerAtMillis))
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp)
                    .background(
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "$time  $title",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun AgendaList(
    items: List<AlarmRepository.AgendaItem>,
    onSelect: (AlarmRepository.AgendaItem) -> Unit,
) {
    val grouped = items.groupBy { eventDate(it.event) }
    LazyColumn {
        grouped.forEach { (date, dayItems) ->
            item(key = "d$date") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    DayHeader(date)
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        dayItems.forEach { item ->
                            EventRow(item, onClick = { onSelect(item) })
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DayHeader(date: LocalDate) {
    val today = kotlinx.datetime.Clock.System.now().toLocalDateTime(tz).date
    val rel = when (date) {
        today -> "今日"
        today + kotlinx.datetime.DatePeriod(days = 1) -> "明日"
        else -> null
    }
    Row(
        Modifier.padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${date.monthNumber}/${date.dayOfMonth}(${date.dayOfWeek.jaShort()})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        rel?.let {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.background(
                    MaterialTheme.colorScheme.primaryContainer,
                    androidx.compose.foundation.shape.RoundedCornerShape(50),
                ).padding(horizontal = 8.dp, vertical = 1.dp),
            ) {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun EventRow(item: AlarmRepository.AgendaItem, onClick: () -> Unit) {
    val ev = item.event
    // 長さゼロ・23時間超のイベントも実質「終日」扱いにして 0:00/~0:00 表記を消す
    val effectiveAllDay = ev.allDay ||
        ev.endMillis - ev.startMillis >= 23L * 3_600_000L ||
        ev.endMillis <= ev.startMillis
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 時刻列
        Column(Modifier.width(52.dp)) {
            Text(
                if (effectiveAllDay) "終日" else timeLabel(ev.startMillis, false),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (!effectiveAllDay) {
                Text(
                    "~" + timeLabel(ev.endMillis, false),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // カレンダー色アクセントバー
        Box(
            Modifier.width(4.dp).height(38.dp)
                .background(
                    Color(item.calendarColor),
                    androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ev.title.ifBlank { "(タイトルなし)" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.muted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
            Text(
                listOfNotNull(
                    item.calendarName,
                    ev.location.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // 鳴動状態アイコン
        Icon(
            if (item.muted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
            contentDescription = if (item.muted) "鳴動OFF" else "鳴動ON",
            tint = if (item.muted) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        if (!item.muted && item.minutesBefore > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                "${item.minutesBefore}分前" +
                    if (item.extraOffsets.isNotEmpty()) " +${item.extraOffsets.size}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventDetailSheet(
    item: AlarmRepository.AgendaItem,
    weatherEnabled: Boolean,
    onOverride: (muted: Boolean, minutesBefore: Int?, extraOffsets: List<Int>?) -> Unit,
    onClearOverride: () -> Unit,
    weatherLoader: suspend (String) -> List<DailyForecast>?,
) {
    val ev = item.event
    var muted by remember { mutableStateOf(item.muted) }
    var minutes by remember { mutableIntStateOf(item.minutesBefore) }
    var extras by remember { mutableStateOf(item.extraOffsets) }
    var forecast by remember { mutableStateOf<List<DailyForecast>?>(null) }

    LaunchedEffect(ev.location, weatherEnabled) {
        if (weatherEnabled && ev.location.isNotBlank()) {
            forecast = weatherLoader(ev.location)
        }
    }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text(ev.title.ifBlank { "(タイトルなし)" }, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "${eventDate(ev).let { "${it.monthNumber}/${it.dayOfMonth}(${it.dayOfWeek.jaShort()})" }}  ${timeLabel(ev.startMillis, ev.allDay)}" +
                (if (ev.allDay) "" else "〜${timeLabel(ev.endMillis, false)}") +
                "  ${item.calendarName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ev.location.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = "場所",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    ev.location,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (ev.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                ev.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 天気: イベント日の予報を1行で
        forecast?.let { fc ->
            val day = eventDate(ev)
            fc.firstOrNull { it.date == day }?.let { f ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.WbCloudy,
                        contentDescription = "当日の天気",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(weatherText(f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = "この予定でアラームを鳴らす",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.weight(1f))
            Switch(
                checked = !muted,
                onCheckedChange = { on ->
                    muted = !on
                    onOverride(!on, if (on) minutes else null, if (on) extras else null)
                },
            )
        }
        if (!muted) {
            Spacer(Modifier.height(8.dp))
            val options = listOf(0 to "開始時", 5 to "5分前", 10 to "10分前", 15 to "15分前", 30 to "30分前", 60 to "1時間前")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.take(3).forEachIndexed { i, (v, label) ->
                    SegmentedButton(
                        selected = minutes == v,
                        onClick = {
                            minutes = v
                            onOverride(false, v, extras)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.drop(3).forEachIndexed { i, (v, label) ->
                    SegmentedButton(
                        selected = minutes == v,
                        onClick = {
                            minutes = v
                            onOverride(false, v, extras)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                    ) { Text(label) }
                }
            }
            // 追加リマインダー: 「+」付きチップ = その時刻にも鳴らす
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                options.filter { it.first != minutes }.forEach { (v, label) ->
                    FilterChip(
                        selected = v in extras,
                        onClick = {
                            extras = if (v in extras) extras - v else (extras + v).sorted()
                            onOverride(false, minutes, extras)
                        },
                        label = { Text("+$label") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
        if (item.hasOverride) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onClearOverride) {
                Text("既定に戻す")
            }
        }
    }
}

// ---- 表示ヘルパ ----

private fun timeLabel(millis: Long, allDay: Boolean): String =
    if (allDay) "終日" else SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(millis))

/** イベントの属する日付。終日イベントは UTC 0時基準なので UTC 解釈、それ以外はローカル。 */
private fun eventDate(ev: CalendarEvent): LocalDate {
    val inst = Instant.fromEpochMilliseconds(ev.startMillis)
    return if (ev.allDay) inst.toLocalDateTime(TimeZone.UTC).date else inst.toLocalDateTime(tz).date
}

private fun weatherText(f: DailyForecast): String =
    "${WeatherApi.describe(f.weatherCode)} ${f.tempMax.toInt()}°/${f.tempMin.toInt()}°" +
        (f.precipitationProbability?.let { " 降水$it%" } ?: "")

private fun kotlinx.datetime.DayOfWeek.jaShort(): String = when (this) {
    kotlinx.datetime.DayOfWeek.SUNDAY -> "日"
    kotlinx.datetime.DayOfWeek.MONDAY -> "月"
    kotlinx.datetime.DayOfWeek.TUESDAY -> "火"
    kotlinx.datetime.DayOfWeek.WEDNESDAY -> "水"
    kotlinx.datetime.DayOfWeek.THURSDAY -> "木"
    kotlinx.datetime.DayOfWeek.FRIDAY -> "金"
    kotlinx.datetime.DayOfWeek.SATURDAY -> "土"
}
