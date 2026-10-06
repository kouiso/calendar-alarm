package com.calendaralarm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmState

/** 予約中または鳴動済みの AlarmInstance スナップショット。再起動復元の種。 */
@Entity(tableName = "scheduled_instances")
data class ScheduledInstanceEntity(
    @PrimaryKey val id: String,
    val triggerAtMillis: Long,
    val title: String,
    val kind: String,
    val eventId: String?,
    val standaloneAlarmId: Long?,
    val snoozeMinutes: Int,
    val soundUri: String?,
    val minutesBefore: Int,
    val eventStartMillis: Long?,
    val snoozeSeq: Int,
    /** PENDING/FIRED/DISMISSED/SNOOZED/MISSED/CANCELLED */
    val state: String,
) {
    fun toInstance(): AlarmInstance = AlarmInstance(
        id = id,
        triggerAtMillis = triggerAtMillis,
        title = title,
        kind = AlarmKind.valueOf(kind),
        eventId = eventId,
        standaloneAlarmId = standaloneAlarmId,
        snoozeMinutes = snoozeMinutes,
        soundUri = soundUri,
        minutesBefore = minutesBefore,
        eventStartMillis = eventStartMillis,
        snoozeSeq = snoozeSeq,
    )

    companion object {
        fun of(i: AlarmInstance, state: AlarmState = AlarmState.PENDING) = ScheduledInstanceEntity(
            id = i.id,
            triggerAtMillis = i.triggerAtMillis,
            title = i.title,
            kind = i.kind.name,
            eventId = i.eventId,
            standaloneAlarmId = i.standaloneAlarmId,
            snoozeMinutes = i.snoozeMinutes,
            soundUri = i.soundUri,
            minutesBefore = i.minutesBefore,
            eventStartMillis = i.eventStartMillis,
            snoozeSeq = i.snoozeSeq,
            state = state.name,
        )
    }
}

/** 単発・繰り返しアラーム本体（例外日込み）。 */
@Entity(tableName = "standalone_alarms")
data class StandaloneAlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    /** 曜日ビットマスク (1=日曜 … 64=土曜)。0=単発。 */
    val daysMask: Int,
    val label: String,
    val soundUri: String?,
    val snoozeMinutes: Int,
    /** 例外日 "yyyy-MM-dd" カンマ区切り。 */
    val exceptionsCsv: String,
)

/** カレンダー単位の鳴動ルール。 */
@Entity(tableName = "calendar_prefs")
data class CalendarPrefEntity(
    @PrimaryKey val calendarId: String,
    val enabled: Boolean,
    val minutesBefore: Int,
)

/** イベント個別のミュート・分数上書き。主キーは instanceKey (calendarId:eventId:startMillis)。 */
@Entity(tableName = "event_overrides")
data class EventOverrideEntity(
    @PrimaryKey val instanceKey: String,
    val muted: Boolean,
    val minutesBefore: Int?,
)

/** 鳴動・予約・キャンセルの監査ログ。「鳴るはずが鳴らなかった」の検証材料。 */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    /** SCHEDULE / FIRE / DISMISS / SNOOZE / CANCEL / MISS / RESYNC / ERROR */
    val action: String,
    val detail: String,
)
