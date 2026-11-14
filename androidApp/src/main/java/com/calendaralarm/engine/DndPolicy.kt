package com.calendaralarm.engine

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * おやすみモード中に OS がアラーム音を通すか (#149)。鳴動音は USAGE_ALARM。
 * 鳴動の判断 ([AlarmService.silencedByDnd]) とトラブルシューティング画面は、必ずこの1つの判定を使う。
 *
 * 通す: すべて許可 / アラームのみ / 優先モードで「アラーム」許可あり / 読めない (鳴らさないより鳴らす方が安全)。
 * 通さない: サイレント (NONE) / 優先モードで「アラーム」許可なし。
 * [policyAllowsAlarms] は優先モードの時だけ見る。読めなかった時は null。
 */
fun dndAllowsAlarms(filter: Int?, policyAllowsAlarms: Boolean?): Boolean = when (filter) {
    null,
    NotificationManager.INTERRUPTION_FILTER_ALL,
    NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
    NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> policyAllowsAlarms ?: true
    else -> false
}

/** いまの割り込みフィルタ。読めなければ null */
fun readInterruptionFilter(context: Context): Int? =
    runCatching { context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter }.getOrNull()

/**
 * 実効中のポリシーが「アラーム」を許可しているか。読めなければ null。
 * 自動ルールで発動した優先モードはユーザーの既定ポリシーとは別物が適用されるため、
 * 実効中の consolidated ポリシーを見る (API 30+)。それ未満は既定ポリシー
 */
fun readPolicyAllowsAlarms(context: Context): Boolean? = runCatching {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return@runCatching null
    val policy = if (Build.VERSION.SDK_INT >= 30) nm.consolidatedNotificationPolicy else nm.notificationPolicy
    (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0
}.getOrNull()

/** いまの端末の状態で、OS がアラーム音を通すか */
fun dndAllowsAlarmsNow(context: Context): Boolean =
    dndAllowsAlarms(readInterruptionFilter(context), readPolicyAllowsAlarms(context))
