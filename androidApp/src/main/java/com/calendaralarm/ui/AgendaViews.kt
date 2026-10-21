package com.calendaralarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendaralarm.data.AlarmRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

private val viewTz = TimeZone.currentSystemDefault()

internal fun eventDateOf(item: AlarmRepository.AgendaItem): LocalDate =
    eventDate(item.event)

/** 月表示: 7列グリッド + ISO週番号列 + 日のイベントドット。日タップで focusDate が変わる。 */
@Composable
fun MonthView(
    month: LocalDate,
    items: List<AlarmRepository.AgendaItem>,
    focusDate: LocalDate?,
    onSelectDay: (LocalDate) -> Unit,
    onSelectEvent: (AlarmRepository.AgendaItem) -> Unit,
) {
    val today = Clock.System.now().toLocalDateTime(viewTz).date
    val first = LocalDate(month.year, month.month, 1)
    val gridStart = first.minus(DatePeriod(days = first.dayOfWeek.isoDayNumber % 7))
    val byDay = items.groupBy { eventDateOf(it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        // 曜日ヘッダ (日曜=赤, 土曜=青。Night UI スペック)
        val sundayRed = if (isSystemInDarkTheme()) Color(0xFFFF8F8F) else Color(0xFFC62828)
        val saturdayBlue = if (isSystemInDarkTheme()) Color(0xFF7D88FF) else Color(0xFF1F6FD1)
        Row {
            listOf("日", "月", "火", "水", "木", "金", "土").forEachIndexed { i, w ->
                Text(
                    w,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (i) {
                        0 -> sundayRed
                        6 -> saturdayBlue
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        repeat(6) { w ->
            val weekStart = gridStart + DatePeriod(days = w * 7)
            val monthEnd = LocalDate(first.year, first.monthNumber, 1)
                .plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
            if (weekStart > monthEnd) return@repeat
            Row(Modifier.padding(vertical = 2.dp)) {
                repeat(7) { d ->
                    val date = weekStart + DatePeriod(days = d)
                    val inMonth = date.month == month.month
                    val dayItems = byDay[date].orEmpty()
                    Column(
                        Modifier.weight(1f).aspectRatio(0.72f)
                            .clickable { onSelectDay(date) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // 34px 日付サークル。テーマ色は「鳴る/オン」だけに使うので中立色にする (#51 PR3)
                        // 今日 = 文字色で塗った丸、選択日 = surfaceVariant の塗りに outline の枠
                        val selected = date == focusDate && date != today
                        Box(
                            Modifier.size(34.dp)
                                .background(
                                    when {
                                        date == today -> MaterialTheme.colorScheme.onSurface
                                        selected -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> Color.Transparent
                                    },
                                    CircleShape,
                                )
                                .border(
                                    width = if (selected) 1.5.dp else 0.dp,
                                    color = if (selected) MaterialTheme.colorScheme.outline else Color.Transparent,
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${date.dayOfMonth}",
                                fontSize = 17.sp,
                                fontFamily = com.calendaralarm.ui.theme.OutfitFontFamily,
                                fontWeight = if (date == today || date == focusDate) {
                                    FontWeight.SemiBold
                                } else FontWeight.Normal,
                                color = when {
                                    date == today -> MaterialTheme.colorScheme.surface
                                    !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                    date.dayOfWeek == DayOfWeek.SUNDAY -> sundayRed
                                    date.dayOfWeek == DayOfWeek.SATURDAY -> saturdayBlue
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        // イベントドット (最大2本、5px)
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            dayItems.take(2).forEach {
                                Box(
                                    Modifier.size(5.dp).background(
                                        Color(it.calendarColor),
                                        CircleShape,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        // 選択日のイベント一覧
        focusDate?.let { day ->
            val dayItems = byDay[day].orEmpty()
            Spacer(Modifier.height(8.dp))
            Text(
                "${day.monthNumber}/${day.dayOfMonth}(${day.dayOfWeek.jaShort()})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            if (dayItems.isEmpty()) {
                Text(
                    "予定なし",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    dayItems.forEach { EventRow(it, onClick = { onSelectEvent(it) }) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 3日表示: 連続3日のタイムライン列。各列に時刻順のイベント行。 */
@Composable
fun ThreeDayView(
    startDate: LocalDate,
    items: List<AlarmRepository.AgendaItem>,
    onSelectDay: (LocalDate) -> Unit,
    onSelectEvent: (AlarmRepository.AgendaItem) -> Unit,
) {
    val today = Clock.System.now().toLocalDateTime(viewTz).date
    val byDay = items.groupBy { eventDateOf(it) }
    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        repeat(3) { i ->
            val date = startDate + DatePeriod(days = i)
            val dayItems = byDay[date].orEmpty()
            Column(
                Modifier.weight(1f).fillMaxSize()
                    .clickable { onSelectDay(date) }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
            ) {
                // 日ヘッダカード: 今日は surfaceVariant。テーマ色は「鳴る/オン」専用なので使わない (#51 PR3)
                Text(
                    "${date.dayOfMonth}(${date.dayOfWeek.jaShort()})",
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .background(
                            if (date == today) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                            RoundedCornerShape(12.dp),
                        ).padding(vertical = 4.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 22.sp,
                    fontFamily = com.calendaralarm.ui.theme.OutfitFontFamily,
                    fontWeight = if (date == today) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                dayItems.forEach { item ->
                    val allDay = item.event.allDay ||
                        item.event.endMillis - item.event.startMillis >= 23L * 3_600_000L
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            .background(
                                Color(item.calendarColor).copy(alpha = 0.15f),
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { onSelectEvent(item) }
                            .padding(6.dp),
                    ) {
                        Text(
                            if (allDay) "終日" else hm(item.event.startMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            item.event.title.ifBlank { "(タイトルなし)" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (dayItems.isEmpty()) {
                    Text(
                        "—",
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

/** 日タイムライン: 0-24時の時間軸にイベントブロックを絶対配置。 */
@Composable
fun DayTimelineView(
    date: LocalDate,
    items: List<AlarmRepository.AgendaItem>,
    onSelectEvent: (AlarmRepository.AgendaItem) -> Unit,
) {
    val hourHeight = 48.dp
    val dayItems = items.filter { eventDateOf(it) == date }
    val allDayItems = dayItems.filter {
        it.event.allDay || it.event.endMillis - it.event.startMillis >= 23L * 3_600_000L
    }
    val timed = dayItems - allDayItems.toSet()
    // 1分ごとに今の時刻を取り直す。開いたまま日付が変わったら前日の画面から線を消すため、今日かどうかもここから出す
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
            nowMillis = System.currentTimeMillis()
        }
    }
    val isToday = date == kotlinx.datetime.Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(viewTz).date
    val nowMinute = minuteOfDay(nowMillis)
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // 今日を開いた時は今の時刻が上から少し下に来るところまで送る (#51 PR3)
    LaunchedEffect(date) {
        if (isToday) {
            val y = with(density) { (hourHeight * (nowMinute / 60f) - hourHeight * 2).toPx() }
            scroll.scrollTo(y.toInt().coerceAtLeast(0))
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp)) {
        if (allDayItems.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                allDayItems.forEach { item ->
                    Text(
                        item.event.title.ifBlank { "(タイトルなし)" },
                        Modifier.background(
                            Color(item.calendarColor).copy(alpha = 0.2f),
                            RoundedCornerShape(8.dp),
                        ).clickable { onSelectEvent(item) }.padding(6.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth().height(hourHeight * 24)) {
            // 時間目盛り
            repeat(24) { h ->
                Row(
                    Modifier.fillMaxWidth().offset(y = hourHeight * h),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "%02d:00".format(h),
                        Modifier.width(44.dp),
                        fontSize = 11.sp,
                        fontFamily = com.calendaralarm.ui.theme.OutfitFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        Modifier.weight(1f).height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    )
                }
            }
            // イベントブロック (開始分→高さ)
            timed.forEach { item ->
                val s = item.event.startMillis
                val e = maxOf(item.event.endMillis, s + 15 * 60_000L) // 最短15分の高さ
                val startMin = minuteOfDay(s)
                val durMin = ((e - s) / 60_000L).coerceAtMost(24L * 60 - startMin)
                val top = hourHeight * (startMin / 60f)
                val h = hourHeight * (durMin / 60f)
                Column(
                    Modifier.padding(start = 48.dp).fillMaxWidth()
                        .offset(y = top).height(h)
                        .background(
                            Color(item.calendarColor).copy(alpha = 0.25f),
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { onSelectEvent(item) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        item.event.title.ifBlank { "(タイトルなし)" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (durMin < 45) 1 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (durMin >= 45) {
                        Text(
                            "${hm(s)}〜${hm(e)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // 今の時刻の線 (#51 PR3)。予定のブロックより上に描く。今日を見ている時だけ出す。
            // テーマ色は「鳴る/オン」専用なので、色表の赤で引く
            if (isToday) {
                val lineColor = com.calendaralarm.ui.theme.StatusColors.nowLine
                val label = "今 %02d:%02d".format(nowMinute / 60, nowMinute % 60)
                Row(
                    Modifier.fillMaxWidth().offset(y = hourHeight * (nowMinute / 60f) - 8.dp)
                        .semantics { contentDescription = label },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 時刻目盛りの文字に重なるので、面の色で下地を敷いて読めるようにする
                    Text(
                        label,
                        Modifier.width(44.dp).background(MaterialTheme.colorScheme.surface),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = com.calendaralarm.ui.theme.OutfitFontFamily,
                        color = lineColor,
                        maxLines = 1,
                    )
                    Box(Modifier.size(8.dp).background(lineColor, CircleShape))
                    Box(Modifier.weight(1f).height(2.dp).background(lineColor))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---- 内部ヘルパ ----

private fun minuteOfDay(millis: Long): Int {
    val t = kotlinx.datetime.Instant.fromEpochMilliseconds(millis).toLocalDateTime(viewTz)
    return t.hour * 60 + t.minute
}

private fun hm(millis: Long): String {
    val t = kotlinx.datetime.Instant.fromEpochMilliseconds(millis).toLocalDateTime(viewTz)
    return "%d:%02d".format(t.hour, t.minute)
}

/** ISO-8601 週番号 (月曜始まり)。その週の木曜が属する年の第1週起点で数える。 */
internal fun isoWeekNumber(date: LocalDate): Int {
    val thursday = date + DatePeriod(days = 4 - date.dayOfWeek.isoDayNumber)
    val jan4 = LocalDate(thursday.year, 1, 4)
    val week1Monday = jan4 - DatePeriod(days = (jan4.dayOfWeek.isoDayNumber + 6) % 7)
    return ((thursday.toEpochDays() - week1Monday.toEpochDays()) / 7) + 1
}
