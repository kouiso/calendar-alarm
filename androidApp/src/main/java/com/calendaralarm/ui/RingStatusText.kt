package com.calendaralarm.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.calendaralarm.R
import com.calendaralarm.shared.logic.RingStatus

/**
 * shared の RingStatus が返す種類と数を、予定画面・設定画面の1行の文言に変える (#291)。
 * shared には文字リソースが無いので、文言は Android の strings.xml (values / values-en) から引く。
 */
internal fun ringStatusLine(res: Resources, summary: RingStatus.Summary): String = when (summary.kind) {
    RingStatus.Kind.MISSING_PERMISSION -> res.getQuantityString(
        R.plurals.ring_status_missing_permission, summary.missingPermissions, summary.missingPermissions,
    )
    RingStatus.Kind.ALL_MUTED -> res.getString(R.string.ring_status_all_muted)
    RingStatus.Kind.ALARM_MUTED -> res.getString(R.string.ring_status_alarm_muted)
    RingStatus.Kind.MISSED_RECENTLY -> res.getQuantityString(
        R.plurals.ring_status_missed_recently, summary.recentMissed, summary.recentMissed,
    )
    RingStatus.Kind.NO_ALARM -> res.getString(R.string.ring_status_no_alarm)
    RingStatus.Kind.READY -> res.getString(R.string.ring_status_ready)
}

@Composable
internal fun ringStatusLine(summary: RingStatus.Summary): String =
    ringStatusLine(LocalContext.current.resources, summary)
