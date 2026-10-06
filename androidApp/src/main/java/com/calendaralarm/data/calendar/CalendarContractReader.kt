package com.calendaralarm.data.calendar

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import com.calendaralarm.shared.model.CalendarEvent
import com.calendaralarm.shared.model.CalendarSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CalendarContract リーダー。
 * 繰り返しイベントは InstancesContract が個別回に展開して返すため、
 * こちら側で RRULE を解釈する必要がない。
 */
class CalendarContractReader(private val context: Context) {

    suspend fun calendars(): List<CalendarSource> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
        )
        val out = mutableListOf<CalendarSource>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection, null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                // VISIBLE=0 のカレンダーも予定は取得されるため、
                // 鳴動は起きるのに設定画面で制御不可にならないよう全件返す
                out += CalendarSource(
                    id = c.getLong(0).toString(),
                    name = c.getString(1) ?: c.getString(2) ?: "カレンダー",
                    accountName = c.getString(2) ?: "",
                    color = c.getInt(3),
                    isPrimary = c.getInt(4) == 1,
                )
            }
        }
        out
    }

    /** [beginMillis, endMillis) に重なるイベントを繰り返し展開込みで返す。 */
    suspend fun events(beginMillis: Long, endMillis: Long): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, beginMillis)
        ContentUris.appendId(builder, endMillis)
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        val out = mutableListOf<CalendarEvent>()
        context.contentResolver.query(
            builder.build(), projection, null, null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val eventId = c.getLong(0)
                val calId = c.getLong(1).toString()
                val begin = c.getLong(5)
                out += CalendarEvent(
                    // 繰り返し回を区別するため instanceKey には開始時刻が入る
                    id = eventId.toString(),
                    calendarId = calId,
                    title = c.getString(2) ?: "",
                    description = c.getString(3) ?: "",
                    location = c.getString(4) ?: "",
                    startMillis = begin,
                    endMillis = c.getLong(6),
                    allDay = c.getInt(7) == 1,
                )
            }
        }
        out
    }
}
