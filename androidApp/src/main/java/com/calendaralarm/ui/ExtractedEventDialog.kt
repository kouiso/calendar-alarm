package com.calendaralarm.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.calendaralarm.shared.model.ExtractedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * AI が抽出した予定の確認ダイアログ (メール→予定の最終段)。
 * 内容を修正して「追加」で端末カレンダーへ書き込む。
 * 書き込み権限はこの時点で初めて要求する (なければボタンが権限要求になる)。
 */
@Composable
fun ExtractedEventDialog(
    event: ExtractedEvent,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf(event.title) }
    var start by remember { mutableStateOf(event.start) }
    var end by remember { mutableStateOf(event.end ?: "") }
    var location by remember { mutableStateOf(event.location) }
    var error by remember { mutableStateOf<String?>(null) }

    fun insert() {
        val ev = event.copy(
            title = title.trim(), start = start.trim(),
            end = end.trim().ifBlank { null }, location = location.trim(),
        )
        scope.launch {
            val err = withContext(Dispatchers.IO) { insertEvent(context, ev) }
            if (err == null) onSaved() else error = err
        }
    }

    // カレンダー書き込み権限は確定時のみ要求する (読み取りとは別権限)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) insert() else error = "カレンダー書き込み権限がありません" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("予定に追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("タイトル") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = start, onValueChange = { start = it },
                    label = { Text(if (event.allDay) "日付 (YYYY-MM-DD)" else "開始 (YYYY-MM-DDTHH:mm)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (!event.allDay) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = end, onValueChange = { end = it },
                        label = { Text("終了 (空=1時間)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = location, onValueChange = { location = it },
                    label = { Text("場所") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_CALENDAR,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) insert() else permissionLauncher.launch(Manifest.permission.WRITE_CALENDAR)
            }) { Text("追加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("戻る") } },
    )
}

/** CalendarContract で最初の書き込み可カレンダーへ挿入。失敗理由を文字列で返す。 */
private fun insertEvent(context: android.content.Context, ev: ExtractedEvent): String? {
    val zone = ZoneId.systemDefault()
    val calId = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
        arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
        CalendarContract.Calendars.IS_PRIMARY + " DESC",
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        ?: return "書き込み可能なカレンダーがありません"

    val (dtStart, dtEnd) = try {
        if (ev.allDay) {
            val d = LocalDate.parse(ev.start, DateTimeFormatter.ISO_LOCAL_DATE)
            val dEnd = ev.end?.let { LocalDate.parse(it) } ?: d.plusDays(1)
            // allDay は UTC 解釈が CalendarProvider の約束
            Pair(
                d.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
                dEnd.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            )
        } else {
            val s = LocalDateTime.parse(ev.start, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val e = ev.end?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                ?: s.plusHours(1)
            Pair(
                s.atZone(zone).toInstant().toEpochMilli(),
                e.atZone(zone).toInstant().toEpochMilli(),
            )
        }
    } catch (e: Exception) {
        return "日時の形式が不正です (${ev.start})"
    }

    val values = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calId)
        put(CalendarContract.Events.TITLE, ev.title)
        put(CalendarContract.Events.DTSTART, dtStart)
        put(CalendarContract.Events.DTEND, dtEnd)
        put(CalendarContract.Events.EVENT_LOCATION, ev.location)
        put(CalendarContract.Events.DESCRIPTION, ev.description)
        put(CalendarContract.Events.ALL_DAY, if (ev.allDay) 1 else 0)
        put(
            CalendarContract.Events.EVENT_TIMEZONE,
            if (ev.allDay) "UTC" else zone.id,
        )
    }
    return context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        ?.let { null } ?: "カレンダーへの書き込みに失敗しました"
}
