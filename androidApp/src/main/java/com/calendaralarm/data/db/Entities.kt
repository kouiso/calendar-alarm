package com.calendaralarm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.AlarmState
import com.calendaralarm.shared.model.EventAction

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
    /** ALARM=全画面鳴動 / NOTIFY=通知のみ (MUTEは予約自体を作らない)。 */
    val delivery: String = EventAction.ALARM.name,
    /** ロック解除まで鳴動を遅延するか。 */
    val muteUntilUnlock: Boolean = false,
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
        delivery = EventAction.valueOf(delivery),
        muteUntilUnlock = muteUntilUnlock,
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
            delivery = i.delivery.name,
            muteUntilUnlock = i.muteUntilUnlock,
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
    /** ロック解除まで鳴動を遅延するか。 */
    val muteUntilUnlock: Boolean = false,
    /** 繰返しモード (RepeatMode.name)。null = 旧形式 (daysMask から導出)。 */
    val repeatMode: String? = null,
    /** INTERVAL_* の間隔。 */
    val repeatInterval: Int = 1,
    /** MONTHLY/INTERVAL_* の起点日 (UTC 0時 epoch millis)。 */
    val repeatAnchorMillis: Long? = null,
)

/** カレンダー単位の鳴動ルール。 */
@Entity(tableName = "calendar_prefs")
data class CalendarPrefEntity(
    @PrimaryKey val calendarId: String,
    val enabled: Boolean,
    val minutesBefore: Int,
    /** 終日イベントを鳴らす現地時刻 (その日0時からの分数)。負数=鳴らさない。 */
    val allDayMinutes: Int = 540,
    /** 追加リマインダーの分数 "15,60" 形式。 */
    val extraOffsetsCsv: String = "",
    /** 開始時刻トリガのアクション (ALARM/NOTIFY/MUTE)。 */
    val startAction: String = EventAction.ALARM.name,
    /** リマインダートリガのアクション (ALARM/NOTIFY/MUTE)。 */
    val reminderAction: String = EventAction.ALARM.name,
)

/**
 * イベント個別のアクション・分数上書き。主キーは instanceKey (calendarId:eventId:startMillis)。
 * action は ALARM/NOTIFY/MUTE。null=既定に従う。
 */
@Entity(tableName = "event_overrides")
data class EventOverrideEntity(
    @PrimaryKey val instanceKey: String,
    val action: String? = null,
    val minutesBefore: Int?,
    /** null=カレンダー既定、空文字=追加なし、"15,60"=追加リマインダー。 */
    val extraOffsetsCsv: String? = null,
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
