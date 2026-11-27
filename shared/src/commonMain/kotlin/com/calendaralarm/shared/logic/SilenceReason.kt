package com.calendaralarm.shared.logic

/** 予定一覧の鳴らない行の理由 (#315)。並びは強い順で、2つ以上当てはまる時は上を使う。文言は各 OS が持つ */
enum class SilenceReason {
    CALENDAR_OFF,
    ACCOUNT_OFF,
    ALL_DAY_OFF,
    EVENT_OFF,
    INVITE,
    EVENT_TYPE,
    FREE_TIME,
    TITLE_CODE,
    CALENDAR_ACTION_OFF,
    MUTE_ALL,
}
