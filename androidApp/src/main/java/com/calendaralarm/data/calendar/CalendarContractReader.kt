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

    /** GoogleCalendarTypes 等、連携レイヤが使う Application Context。 */
    fun appContext(): Context = context

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
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.ORGANIZER,
        )
        val out = mutableListOf<CalendarEvent>()
        val baseEventIds = mutableSetOf<Long>()
        // 招待判定にカレンダー所有者アカウントが要るので先に拾う
        val accountByCal = calendars().associate { it.id to it.accountName }
        context.contentResolver.query(
            builder.build(), projection, null, null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val eventId = c.getLong(0)
                val calId = c.getLong(1).toString()
                val begin = c.getLong(5)
                val selfStatus = c.getInt(8)
                val organizer = c.getString(9)
                baseEventIds += eventId
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
                    inviteStatus = selfStatusToInvite(
                        selfStatus, organizer, accountByCal[calId],
                    ),
                )
            }
        }
        // カレンダー側リマインダーを基底イベントIDで一括取得し各回へ配る
        val reminders = eventReminders(baseEventIds)
        // Google Calendar API の iCalUID 照合用に UID_2445 を基底イベント単位で拾う
        val uids = eventUids(baseEventIds)
        out.map {
            it.copy(
                calendarReminderMinutes = reminders[it.id.toLongOrNull()] ?: emptyList(),
                iCalUID = uids[it.id.toLongOrNull()],
            )
        }
    }

    /** Events テーブルから基底イベントID群の UID_2445 (iCalUID) を一括取得する。 */
    private suspend fun eventUids(eventIds: Set<Long>): Map<Long, String> =
        withContext(Dispatchers.IO) {
            if (eventIds.isEmpty()) return@withContext emptyMap()
            val out = mutableMapOf<Long, String>()
            eventIds.chunked(200).forEach { chunk ->
                val selection = "${CalendarContract.Events._ID} IN (" +
                    chunk.joinToString(",") + ")"
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(
                        CalendarContract.Events._ID,
                        CalendarContract.Events.UID_2445,
                    ),
                    selection, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val uid = c.getString(1)
                        if (!uid.isNullOrEmpty()) out[c.getLong(0)] = uid
                    }
                }
            }
            out
        }

    /**
     * 出席ステータス → InviteStatus。主催者=カレンダー所有者自身の予定は
     * 「招待」ではないので null を返す (フィルタ対象外)。
     */
    private fun selfStatusToInvite(
        status: Int,
        organizer: String?,
        calendarAccount: String?,
    ): com.calendaralarm.shared.model.InviteStatus? {
        if (status == CalendarContract.Attendees.ATTENDEE_STATUS_NONE) return null
        if (organizer != null && calendarAccount != null &&
            organizer.equals(calendarAccount, ignoreCase = true)
        ) return null
        return when (status) {
            CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED ->
                com.calendaralarm.shared.model.InviteStatus.ACCEPTED
            CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE ->
                com.calendaralarm.shared.model.InviteStatus.TENTATIVE
            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED ->
                com.calendaralarm.shared.model.InviteStatus.DECLINED
            CalendarContract.Attendees.ATTENDEE_STATUS_INVITED ->
                com.calendaralarm.shared.model.InviteStatus.NEEDS_ACTION
            else -> null
        }
    }

    /**
     * Reminders テーブルから基底イベントID群の通知分数を取得する。
     * MINUTES=-1 (MINUTES_DEFAULT) は「システム既定」を意味し、実値は端末に
     * 依存するためここでは解決せず -1 のまま返す (Repository 側でユーザー設定の
     * 既定分数に置き換える)。METHOD が DEFAULT/ALERT 以外 (EMAIL/SMS) の行は
     * 端末が通知を処理しないため取り込まない。-1 以外の負値は未定義として捨てる。
     */
    private suspend fun eventReminders(eventIds: Set<Long>): Map<Long, List<Int>> =
        withContext(Dispatchers.IO) {
            if (eventIds.isEmpty()) return@withContext emptyMap()
            val out = mutableMapOf<Long, MutableList<Int>>()
            // EVENT_ID IN (...) のバッチ。ID が多いと URI 長制限に触れるため 200 件ずつ。
            eventIds.chunked(200).forEach { chunk ->
                val selection = "${CalendarContract.Reminders.EVENT_ID} IN (" +
                    chunk.joinToString(",") + ")"
                context.contentResolver.query(
                    CalendarContract.Reminders.CONTENT_URI,
                    arrayOf(
                        CalendarContract.Reminders.EVENT_ID,
                        CalendarContract.Reminders.MINUTES,
                        CalendarContract.Reminders.METHOD,
                    ),
                    selection, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val method = c.getInt(2)
                        if (method != CalendarContract.Reminders.METHOD_DEFAULT &&
                            method != CalendarContract.Reminders.METHOD_ALERT
                        ) continue
                        val minutes = c.getInt(1)
                        // -1 (MINUTES_DEFAULT) は意味のある値なので通す。それ以外の負値は捨てる
                        if (minutes < 0 && minutes != CalendarContract.Reminders.MINUTES_DEFAULT) continue
                        val id = c.getLong(0)
                        out.getOrPut(id) { mutableListOf() } += minutes
                    }
                }
            }
            out
        }

}
