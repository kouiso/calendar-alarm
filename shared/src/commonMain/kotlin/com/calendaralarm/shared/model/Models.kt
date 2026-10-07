package com.calendaralarm.shared.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** アラーム種別。鳴動経路は共通で、種別ごとに鳴動画面の文言を変える。 */
enum class AlarmKind { EVENT, STANDALONE, TIMER }

/**
 * 鳴動アクションの3状態 (元アプリ仕様)。
 * ALARM=フルスクリーン鳴動、NOTIFY=通知のみ、MUTE=何もしない。
 */
enum class EventAction { ALARM, NOTIFY, MUTE }

/** 招待された予定の参加可否。null=自分の予定 (フィルタ対象外)。 */
enum class InviteStatus { ACCEPTED, TENTATIVE, NEEDS_ACTION, DECLINED }

/**
 * 予定タイトルコード (元アプリ仕様)。
 * タイトルに含まれるコードで鳴動を強制: always=必ずアラーム、never=鳴らさない。
 * 各最大15個。適用範囲は開始時刻とリマインダーで分けられる。
 */
@Serializable
data class TitleCodeSettings(
    val alwaysCodes: List<String> = emptyList(),
    val neverCodes: List<String> = emptyList(),
    /** 開始時刻トリガにコードを適用するか。 */
    val applyToStart: Boolean = true,
    /** リマインダートリガにコードを適用するか。 */
    val applyToReminders: Boolean = true,
) {
    fun alwaysMatch(title: String): Boolean =
        alwaysCodes.any { it.isNotBlank() && title.contains(it, ignoreCase = true) }

    fun neverMatch(title: String): Boolean =
        neverCodes.any { it.isNotBlank() && title.contains(it, ignoreCase = true) }
}

/**
 * 招待予定のフィルタ (元アプリ仕様)。
 * 各ステータスを鳴らすかどうか。招待でない予定は常に対象。
 */
@Serializable
data class InviteFilter(
    val accepted: Boolean = true,
    val tentative: Boolean = true,
    val needsAction: Boolean = true,
    val declined: Boolean = false,
) {
    fun allows(status: InviteStatus?): Boolean = when (status) {
        null -> true
        InviteStatus.ACCEPTED -> accepted
        InviteStatus.TENTATIVE -> tentative
        InviteStatus.NEEDS_ACTION -> needsAction
        InviteStatus.DECLINED -> declined
    }
}

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
    /** 招待予定の自分の参加ステータス。null=招待でない (自分の予定)。 */
    val inviteStatus: InviteStatus? = null,
    /** カレンダー側に設定済みのリマインダー (開始何分前かの分数リスト)。 */
    val calendarReminderMinutes: List<Int> = emptyList(),
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
    /** 開始時刻トリガの既定アクション。 */
    val startAction: EventAction = EventAction.ALARM,
    /** 追加リマインダー/予定側リマインダーの既定アクション。 */
    val reminderAction: EventAction = EventAction.ALARM,
)

/**
 * イベント個別の上書き設定。
 * action: null=既定に従う、MUTE=鳴らさない、NOTIFY=通知のみ、ALARM=必ず鳴らす。
 * タイトルコードよりユーザーの明示操作を優先する (イベント1個への指定が最も具体的)。
 */
@Serializable
data class EventOverride(
    val action: EventAction? = null,
    val minutesBefore: Int? = null,
    /** null はカレンダー既定を使う。空リストは「追加リマインダーなし」。 */
    val extraOffsets: List<Int>? = null,
) {
    val muted: Boolean get() = action == EventAction.MUTE
}

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
    /** 元アプリの「ロック解除までミュート」: 発火時に端末ロック中なら鳴らさず、
     *  解除された時点で鳴動を開始する。 */
    val muteUntilUnlock: Boolean = false,
    /** 繰返しモード。null は旧形式 (daysOfWeek 空→ONCE, 非空→WEEKLY)。 */
    val repeatMode: RepeatMode? = null,
    /** INTERVAL_* の間隔 (x日ごと/x週ごと/xヶ月ごと)。 */
    val repeatInterval: Int = 1,
    /** MONTHLY/INTERVAL_* の起点日 (UTC 0時の epoch millis)。 */
    val repeatAnchorMillis: Long? = null,
) {
    /** 旧データ (repeatMode 未設定) を含む実効モード。 */
    fun effectiveRepeatMode(): RepeatMode =
        repeatMode ?: if (daysOfWeek.isEmpty()) RepeatMode.ONCE else RepeatMode.WEEKLY
}

/** 単発アラームの繰返し種別。元アプリ: 1回のみ/曜日/毎月/x日ごと/x週ごと/xヶ月ごと。 */
enum class RepeatMode {
    ONCE,
    WEEKLY,
    MONTHLY,
    INTERVAL_DAYS,
    INTERVAL_WEEKS,
    INTERVAL_MONTHS,
}

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
    /** 鳴動方法。ALARM=全画面鳴動、NOTIFY=通知のみ。MUTE は展開段階で除外済み。 */
    val delivery: EventAction = EventAction.ALARM,
    /** ロック解除まで鳴動を遅延するか (STANDALONE由来のみ)。 */
    val muteUntilUnlock: Boolean = false,
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

/** 現在の天気 (ヘッダー/鳴動画面用)。 */
@Serializable
data class CurrentWeather(
    val weatherCode: Int,
    val temperature: Double,
    /** 昼なら true。鳴動画面の背景演出で昼夜を分ける。 */
    val isDay: Boolean,
)

/** 1時間分の予報 (ヘッダーの時間別予報チップ用)。 */
@Serializable
data class HourlyWeather(
    val epochMillis: Long,
    val weatherCode: Int,
    val temperature: Double,
    val precipitationProbability: Int?,
)

/** 現在+時間別予報の束。 */
@Serializable
data class NowForecast(
    val current: CurrentWeather,
    val hourly: List<HourlyWeather>,
)

/** Instant を延長してもコードを読みやすくするだけの小さなエイリアス。 */
val Instant.isPast: Boolean
    get() = this < kotlinx.datetime.Clock.System.now()
