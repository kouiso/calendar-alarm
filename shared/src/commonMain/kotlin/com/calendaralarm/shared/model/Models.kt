package com.calendaralarm.shared.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** アラーム種別。鳴動経路は共通で、種別ごとに鳴動画面の文言を変える。 */
enum class AlarmKind { EVENT, STANDALONE, TIMER }

/** 端末カレンダープロバイダが返すカレンダー。 */
@Serializable
data class CalendarSource(
    val id: String,
    val name: String,
    val accountName: String,
    val color: Int,
    val isPrimary: Boolean = false,
)

/** イベント。繰り返しイベントはプラットフォーム側で個別回に展開済みの形で渡す。 */
@Serializable
data class CalendarEvent(
    val id: String,
    val calendarId: String,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timezone: String? = null,
) {
    /** 同一イベントの複数回を区別するため、開始時刻込みの安定キーを返す。 */
    val instanceKey: String get() = "$calendarId:$id:$startMillis"
}

/** カレンダー/イベント単位の鳴動ルール。minutesBefore=0 は開始時刻ちょうど。 */
@Serializable
data class AlarmRule(
    val enabled: Boolean = true,
    val minutesBefore: Int = 0,
    /** 終日イベントを鳴らす現地時刻 (その日の0時からの分数)。負数は「終日イベントを鳴らさない」。 */
    val allDayMinutes: Int = 540,
    /** メインの鳴動に追加するリマインダー (開始何分前かの分数リスト)。 */
    val extraOffsets: List<Int> = emptyList(),
)

/** イベント個別の上書き設定（ミュート or 分数の上書き）。 */
@Serializable
data class EventOverride(
    val muted: Boolean = false,
    val minutesBefore: Int? = null,
    /** null はカレンダー既定を使う。空リストは「追加リマインダーなし」。 */
    val extraOffsets: List<Int>? = null,
)

/** 例外なし・例外付きの単発/曜日繰り返しアラーム。 */
@Serializable
data class StandaloneAlarm(
    val id: Long = 0,
    val enabled: Boolean = true,
    val hour: Int,
    val minute: Int,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val label: String = "",
    val soundUri: String? = null,
    val snoozeMinutes: Int = 10,
    /** この日は鳴らない例外日。 */
    val exceptions: Set<LocalDate> = emptySet(),
)

/** 鳴動エンジンが実際にスケジュールする1回分のアラーム。 */
@Serializable
data class AlarmInstance(
    /** 再生成しても同じになる安定キー（差分適用のキー）。 */
    val id: String,
    val triggerAtMillis: Long,
    val title: String,
    val kind: AlarmKind,
    val eventId: String? = null,
    val standaloneAlarmId: Long? = null,
    val snoozeMinutes: Int = 10,
    val soundUri: String? = null,
    val minutesBefore: Int = 0,
    val eventStartMillis: Long? = null,
    /** スヌーズ由来のインスタンスは1、それ以外は0。 */
    val snoozeSeq: Int = 0,
) {
    fun snoozed(nextTriggerMillis: Long): AlarmInstance =
        // URI 経由で渡す都合上 '#' は使えない (fragment 扱いで lastPathSegment が化ける)
        copy(id = "$id:snz${snoozeSeq + 1}", triggerAtMillis = nextTriggerMillis, snoozeSeq = snoozeSeq + 1)
}

/** 鳴動結果の状態。ScheduledInstanceEntity.state と対応。 */
enum class AlarmState { PENDING, FIRED, DISMISSED, SNOOZED, MISSED, CANCELLED }

@Serializable
data class DailyForecast(
    val date: LocalDate,
    val weatherCode: Int,
    val tempMax: Double,
    val tempMin: Double,
    val precipitationProbability: Int?,
)

@Serializable
data class GeoPoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/** Instant を延長してもコードを読みやすくするだけの小さなエイリアス。 */
val Instant.isPast: Boolean
    get() = this < kotlinx.datetime.Clock.System.now()
