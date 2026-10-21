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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        // デザイン正本の推奨 (「タイムライン」は狭い幅で2行に折れる)
        TIMELINE -> "時間軸"
    }
}

/**
 * 予定アジェンダ。カレンダーのイベントを日付ごとに並べ、
 * 行ごとに鳴動ON/OFFと事前分数を直接いじれるのが本アプリの主操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(
    repository: AlarmRepository,
    onOpenPermissions: () -> Unit = {},
    onOpenTroubleshooting: () -> Unit = {},
) {
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
    // 鳴動の状態 (#51 PR2): 足りない許可と直近24時間の見逃しから1行にまとめる
    val resumeTick = rememberResumeTick()
    val missingPermissions = remember(resumeTick) { probePermissions(context).count { !it.value } }
    val recentMissedFlow = remember(resumeTick) { repository.recentMissedFlow() }
    val recentMissed by recentMissedFlow.collectAsState(initial = 0)
    val ringStatus = com.calendaralarm.shared.logic.RingStatus.summarize(
        missingPermissions = missingPermissions,
        recentMissed = recentMissed,
        hasNextAlarm = nextAlarm != null,
    )

    Column(Modifier.fillMaxSize()) {
        // Night UI: 日付 H1 + 天気ピルは WeatherHeaderRow が担う
        Text(
            SimpleDateFormat("M月d日 (E)", Locale.JAPAN).format(Date()),
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 58.dp),
        )
        // 次のアラーム欄は小さくし、その下に鳴動の状態を1行出す (#51 PR2)
        NextAlarmBanner(nextAlarm?.title, nextAlarm?.triggerAtMillis, ringStatus.line)
        if (ringStatus.showPermissionBanner) {
            StatusBanner(
                "許可が${ringStatus.missingPermissions}つ足りません。鳴らない・止められないことがあります",
                action = "直す",
                onAction = onOpenPermissions,
            )
        }
        if (ringStatus.showMissedBanner) {
            StatusBanner(
                "直近24時間に鳴らせなかったアラームが${ringStatus.recentMissed}件あります",
                action = "原因を見る",
                onAction = onOpenTroubleshooting,
            )
        }

        // ビュー切替 (Night UI 4-way segment) + 検索 + 同期
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val modes = listOf(
                ViewMode.LIST, ViewMode.THREE_DAY, ViewMode.MONTH, ViewMode.TIMELINE,
            )
            NightSegment(
                labels = modes.map { it.label() },
                selected = modes.indexOf(viewMode),
                onSelect = { i ->
                    viewMode = modes[i]
                    if (modes[i] != ViewMode.LIST) {
                        focusDate = kotlinx.datetime.Clock.System.now()
                            .toLocalDateTime(tz).date
                    }
                },
                modifier = Modifier.weight(1f),
            )
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

        // ヘッダー天気 (元アプリ: 現在気温+時間別予報)。地点設定がある時だけ出す。
        val weatherLoc = settings?.weatherLocation.orEmpty()
        var nowForecast by remember { mutableStateOf<com.calendaralarm.shared.model.NowForecast?>(null) }
        LaunchedEffect(weatherLoc, settings?.weatherHeaderEnabled, refreshKey) {
            nowForecast = if (settings?.weatherHeaderEnabled == true && weatherLoc.isNotBlank()) {
                runCatching { app.container.weather.nowForLocation(weatherLoc, hours = 9) }.getOrNull()
            } else {
                null
            }
        }
        nowForecast?.let { nf -> WeatherHeaderRow(nf) }

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
                        if (searchQuery.isNotBlank()) "該当する予定はありません" else "この先2週間の予定はありません",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (searchQuery.isBlank()) {
                        // 予定が無い時の画面 (#51 PR2): 何をすれば鳴るようになるかを1行で
                        Text(
                            "カレンダーに予定を入れると、ここに並んで前もって鳴ります",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp),
                        )
                    }
                }
            }
            else -> when (viewMode) {
                ViewMode.LIST -> AgendaList(
                    items = filtered,
                    onSelect = { selected = it },
                    // 行の右端のスイッチ: その予定だけ鳴らす／鳴らさないを決める (#51 PR2)
                    onToggle = { item, on ->
                        scope.launch {
                            repository.setEventOverride(
                                item.event.instanceKey,
                                if (on) EventAction.ALARM else EventAction.MUTE,
                                null,
                            )
                            refreshKey++
                        }
                    },
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
private fun NextAlarmBanner(title: String?, triggerAtMillis: Long?, statusLine: String) {
    val accent = MaterialTheme.colorScheme.primary
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.15f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "次のアラーム",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = accent,
                    )
                    Text(
                        title ?: "なし",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (triggerAtMillis != null) {
                    Text(
                        SimpleDateFormat("H:mm", Locale.JAPAN).format(Date(triggerAtMillis)),
                        fontSize = 36.sp,
                        fontFamily = com.calendaralarm.ui.theme.OutfitFontFamily,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // 鳴動の状態 (1行)
            Text(
                statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.semantics { contentDescription = "鳴動の状態: $statusLine" },
            )
        }
    }
}

/** 許可が足りない時・鳴らせなかった時のバナー (#51 PR2)。押すと直す画面へ行く */
@Composable
private fun StatusBanner(message: String, action: String, onAction: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun AgendaList(
    items: List<AlarmRepository.AgendaItem>,
    onSelect: (AlarmRepository.AgendaItem) -> Unit,
    onToggle: ((AlarmRepository.AgendaItem, Boolean) -> Unit)? = null,
) {
    val grouped = items.groupBy { eventDate(it.event) }
    LazyColumn {
        grouped.forEach { (date, dayItems) ->
            item(key = "d$date") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    DayHeader(date)
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        dayItems.forEach { item ->
                            EventRow(
                                item,
                                onClick = { onSelect(item) },
                                onToggle = onToggle?.let { t -> { on: Boolean -> t(item, on) } },
                            )
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
internal fun EventRow(
    item: AlarmRepository.AgendaItem,
    onClick: () -> Unit,
    /** 渡された時は右端を鳴らす／鳴らさないのスイッチにする (予定一覧)。他の表示はアイコンのまま */
    onToggle: ((Boolean) -> Unit)? = null,
) {
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
        // 時刻列 (Night UI: Outfit 20/500)
        Column(Modifier.width(52.dp)) {
            Text(
                if (effectiveAllDay) "終日" else timeLabel(ev.startMillis, false),
                fontSize = 20.sp,
                fontFamily = com.calendaralarm.ui.theme.OutfitFontFamily,
                fontWeight = FontWeight.Medium,
            )
            if (!effectiveAllDay) {
                Text(
                    "~" + timeLabel(ev.endMillis, false),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // カレンダー色ドット
        Box(
            Modifier.size(8.dp)
                .background(Color(item.calendarColor), CircleShape),
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
        if (onToggle == null) {
            Icon(
                icon,
                contentDescription = when {
                    item.muted -> "鳴動OFF"
                    item.startAction == EventAction.ALARM -> "アラーム"
                    else -> "通知のみ"
                },
                tint = iconTint,
            )
        }
        if (!item.muted && item.minutesBefore > 0) {
            Spacer(Modifier.width(6.dp))
            // 「N分前」は accent-soft の小チップ
            Box(
                Modifier.background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                ).padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    "${item.minutesBefore}分前" +
                        if (item.extraOffsets.isNotEmpty()) " +${item.extraOffsets.size}" else "",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (onToggle != null) {
            Spacer(Modifier.width(6.dp))
            // カレンダーごと、または終日の予定を鳴らさない設定の時は、予定のスイッチを入れても鳴らないので押せなくする
            Switch(
                checked = !item.muted,
                onCheckedChange = onToggle,
                enabled = !item.lockedByCalendar,
                modifier = Modifier.semantics {
                    contentDescription = when {
                        item.calendarOff -> "カレンダーが鳴らさない設定です。設定のカレンダー欄で変えられます"
                        item.allDayOff -> "このカレンダーは終日の予定を鳴らさない設定です。設定のカレンダー欄で変えられます"
                        else -> "${ev.title.ifBlank { "予定" }}で鳴らす"
                    }
                },
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
    // 「HH:mm の予報」に出す取得時刻。日ごとの予報なので、予定の時刻ではなくいつ取った予報かを出す
    var forecastAtMillis by remember { mutableStateOf(0L) }

    LaunchedEffect(ev.location, weatherEnabled) {
        if (weatherEnabled && ev.location.isNotBlank()) {
            forecast = weatherLoader(ev.location)
            forecastAtMillis = System.currentTimeMillis()
        }
    }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        // 予定詳細はタイトルを主役にする (#51 PR3: 28〜34 の太字)
        Text(
            ev.title.ifBlank { "(タイトルなし)" },
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
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

        // 予定の日の天気を小さいカードで出す (#51 PR3: 84 の大きい気温はやめる)
        forecast?.let { fc ->
            val day = eventDate(ev)
            fc.firstOrNull { it.date == day }?.let { f ->
                Spacer(Modifier.height(12.dp))
                EventWeatherCard(f, ev.location, forecastAtMillis)
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

/** 予定詳細の小さい天気カード: アイコン40 + 気温34 + 天気・降水 + 「地点・HH:mm の予報」 */
@Composable
private fun EventWeatherCard(f: DailyForecast, location: String, forecastAtMillis: Long) {
    Card(
        Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(weatherEmoji(f.weatherCode), fontSize = 40.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                "${f.tempMax.toInt()}°",
                fontFamily = com.calendaralarm.ui.theme.OutfitFontFamily,
                fontWeight = FontWeight.Light,
                fontSize = 34.sp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    weatherText(f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "$location・${SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(forecastAtMillis))} の予報",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
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

/** WMO コード → 絵文字。アイコン依存を増やさず視認性優先。 */
private fun weatherEmoji(code: Int): String = when (code) {
    0 -> "☀️"
    1 -> "🌤️"
    2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 56, 57 -> "🌦️"
    61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
    71, 73, 75, 77, 85, 86 -> "❄️"
    95, 96, 99 -> "⛈️"
    else -> "—"
}

/** 現在気温 + 時間別予報チップの1行 (元アプリのヘッダー予報に相当)。 */
@Composable
private fun WeatherHeaderRow(f: com.calendaralarm.shared.model.NowForecast) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${weatherEmoji(f.current.weatherCode)} ${f.current.temperature.toInt()}°",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            WeatherApi.describe(f.current.weatherCode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        val fmt = remember { SimpleDateFormat("H時", Locale.getDefault()) }
        f.hourly.take(9).forEach { h ->
            Text(
                "${fmt.format(Date(h.epochMillis))} ${weatherEmoji(h.weatherCode)} ${h.temperature.toInt()}°",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}
