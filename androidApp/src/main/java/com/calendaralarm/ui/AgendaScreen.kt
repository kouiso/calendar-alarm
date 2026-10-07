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
import com.calendaralarm.shared.model.EventAction
import com.calendaralarm.shared.model.DailyForecast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.vector.ImageVector
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

/** 予定画面のビュー種別 (元アプリ: 一覧/月/3日/日タイムライン)。 */
internal enum class ViewMode {
    LIST, MONTH, THREE_DAY, TIMELINE;

    fun icon(): ImageVector = when (this) {
        LIST -> Icons.Default.ViewAgenda
        MONTH -> Icons.Default.CalendarMonth
        THREE_DAY -> Icons.Default.ViewColumn
        TIMELINE -> Icons.Default.Schedule
    }

    fun label(): String = when (this) {
        LIST -> "一覧"
        MONTH -> "月"
        THREE_DAY -> "3日"
        TIMELINE -> "タイムライン"
    }
}

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
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var focusDate by remember {
        mutableStateOf(kotlinx.datetime.Clock.System.now().toLocalDateTime(tz).date)
    }
    val pending by repository.pendingFlow().collectAsState(initial = emptyList())
    val settings by app.container.settings.flow.collectAsState(initial = null)

    // 月表示は今月を埋めるため42日分、それ以外は14日分を取る
    val fetchDays = if (viewMode == ViewMode.MONTH) 42 else 14
    LaunchedEffect(refreshKey, fetchDays) {
        items = repository.upcomingEvents(days = fetchDays)
    }

    val nextAlarm = pending.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        // 次のアラーム帯: いつ鳴るか常時見せるのが信頼感の肝
        if (nextAlarm != null) {
            NextAlarmBanner(nextAlarm.title, nextAlarm.triggerAtMillis)
        }

        // ビュー切替 + 検索 + 同期 (元アプリ: 一覧/月/3日/日タイムライン + 検索)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewMode.entries.forEach { mode ->
                IconButton(onClick = {
                    viewMode = mode
                    if (mode != ViewMode.LIST) {
                        focusDate = kotlinx.datetime.Clock.System.now()
                            .toLocalDateTime(tz).date
                    }
                }) {
                    Icon(
                        mode.icon(),
                        contentDescription = mode.label(),
                        tint = if (viewMode == mode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                searchOpen = !searchOpen
                if (!searchOpen) searchQuery = ""
            }) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "予定を検索",
                    tint = if (searchOpen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = {
                SyncWorker.enqueueNow(context)
                refreshKey++
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "同期")
            }
        }
        if (searchOpen) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("タイトルで絞り込み") },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "クリア")
                        }
                    }
                },
            )
        }

        val list = items
        val filtered = list?.let { l ->
            if (searchQuery.isBlank()) l else l.filter {
                it.event.title.contains(searchQuery, ignoreCase = true)
            }
        }
        when {
            filtered == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (searchQuery.isNotBlank()) "該当なし" else "予定なし",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> when (viewMode) {
                ViewMode.LIST -> AgendaList(
                    items = filtered,
                    onSelect = { selected = it },
                )
                ViewMode.MONTH -> MonthView(
                    month = focusDate,
                    items = filtered,
                    focusDate = focusDate,
                    onSelectDay = { focusDate = it },
                    onSelectEvent = { selected = it },
                )
                ViewMode.THREE_DAY -> ThreeDayView(
                    startDate = focusDate,
                    items = filtered,
                    onSelectDay = { focusDate = it },
                    onSelectEvent = { selected = it },
                )
                ViewMode.TIMELINE -> DayTimelineView(
                    date = focusDate,
                    items = filtered,
                    onSelectEvent = { selected = it },
                )
            }
        }
    }

    selected?.let { item ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            EventDetailSheet(
                item = item,
                weatherEnabled = settings?.weatherEnabled ?: true,
                onOverride = { action, minutes, extraOffsets ->
                    scope.launch {
                        repository.setEventOverride(item.event.instanceKey, action, minutes, extraOffsets)
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
internal fun EventRow(item: AlarmRepository.AgendaItem, onClick: () -> Unit) {
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
        // 鳴動状態アイコン (アラーム/通知のみ/ミュートの3状態)
        val (icon, iconTint) = when {
            item.startAction == EventAction.MUTE && item.reminderAction == EventAction.MUTE ->
                Icons.Default.NotificationsOff to
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            item.startAction == EventAction.ALARM ->
                Icons.Default.Notifications to MaterialTheme.colorScheme.primary
            else ->
                Icons.Default.Notifications to MaterialTheme.colorScheme.tertiary
        }
        Icon(
            icon,
            contentDescription = when {
                item.muted -> "鳴動OFF"
                item.startAction == EventAction.ALARM -> "アラーム"
                else -> "通知のみ"
            },
            tint = iconTint,
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
    onOverride: (action: EventAction, minutesBefore: Int?, extraOffsets: List<Int>?) -> Unit,
    onClearOverride: () -> Unit,
    weatherLoader: suspend (String) -> List<DailyForecast>?,
) {
    val ev = item.event
    // 3状態。開始とリマインダーが混在する場合は開始側を初期値に
    var action by remember { mutableStateOf(item.startAction) }
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
        // アクション3択: アラーム鳴動 / 通知のみ / 鳴らさない
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(
                EventAction.ALARM to "アラーム",
                EventAction.NOTIFY to "通知のみ",
                EventAction.MUTE to "鳴らさない",
            ).forEachIndexed { i, (a, label) ->
                SegmentedButton(
                    selected = action == a,
                    onClick = {
                        action = a
                        onOverride(a, if (a == EventAction.MUTE) null else minutes,
                            if (a == EventAction.MUTE) null else extras)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                ) { Text(label) }
            }
        }
        if (action != EventAction.MUTE) {
            Spacer(Modifier.height(8.dp))
            val options = listOf(0 to "開始時", 5 to "5分前", 10 to "10分前", 15 to "15分前", 30 to "30分前", 60 to "1時間前")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.take(3).forEachIndexed { i, (v, label) ->
                    SegmentedButton(
                        selected = minutes == v,
                        onClick = {
                            minutes = v
                            onOverride(action, v, extras)
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
                            onOverride(action, v, extras)
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
                            onOverride(action, minutes, extras)
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
internal fun eventDate(ev: CalendarEvent): LocalDate {
    val inst = Instant.fromEpochMilliseconds(ev.startMillis)
    return if (ev.allDay) inst.toLocalDateTime(TimeZone.UTC).date else inst.toLocalDateTime(tz).date
}

private fun weatherText(f: DailyForecast): String =
    "${WeatherApi.describe(f.weatherCode)} ${f.tempMax.toInt()}°/${f.tempMin.toInt()}°" +
        (f.precipitationProbability?.let { " 降水$it%" } ?: "")

internal fun kotlinx.datetime.DayOfWeek.jaShort(): String = when (this) {
    kotlinx.datetime.DayOfWeek.SUNDAY -> "日"
    kotlinx.datetime.DayOfWeek.MONDAY -> "月"
    kotlinx.datetime.DayOfWeek.TUESDAY -> "火"
    kotlinx.datetime.DayOfWeek.WEDNESDAY -> "水"
    kotlinx.datetime.DayOfWeek.THURSDAY -> "木"
    kotlinx.datetime.DayOfWeek.FRIDAY -> "金"
    kotlinx.datetime.DayOfWeek.SATURDAY -> "土"
}
