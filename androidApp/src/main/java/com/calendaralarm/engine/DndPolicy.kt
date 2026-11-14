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
    NotificationManager.INTERRUPTION_FILTER_NONE -> false
    // 知らない値は読めないのと同じに扱い、鳴らす側に倒す
    else -> true
}

/** いまの割り込みフィルタ。読めなければ null */
fun readInterruptionFilter(context: Context): Int? =
    runCatching { context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter }.getOrNull()

/**
 * 実効中のポリシーが「アラーム」を許可しているか。読めなければ null。
 * 自動ルールで発動した優先モードはユーザーの既定ポリシーとは別物が適用されるため、
 * 実効中の consolidated ポリシーを見る (API 30+)。それ未満は既定ポリシー。
 * 「アラーム」の項目 (PRIORITY_CATEGORY_ALARMS) は API 28 からある。API 26/27 の優先モードは
 * アラームを常に通すので、そこでは許可ありとして返す (項目が無いのを「許可なし」と読むと、黙って鳴らさなくなる)
 */
fun readPolicyAllowsAlarms(context: Context): Boolean? = runCatching {
    if (Build.VERSION.SDK_INT < 28) return@runCatching true
    val nm = context.getSystemService(NotificationManager::class.java) ?: return@runCatching null
    val policy = if (Build.VERSION.SDK_INT >= 30) nm.consolidatedNotificationPolicy else nm.notificationPolicy
    policyAllowsAlarms(Build.VERSION.SDK_INT, policy.priorityCategories)
}.getOrNull()

/** 優先モードのポリシーが「アラーム」を通すか。API 28 未満は項目が無く、優先モードはアラームを常に通す */
fun policyAllowsAlarms(sdkInt: Int, priorityCategories: Int): Boolean =
    sdkInt < 28 || (priorityCategories and PRIORITY_CATEGORY_ALARMS_BIT) != 0

/** NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS (API 28 で追加。値は 32) */
private const val PRIORITY_CATEGORY_ALARMS_BIT = 1 shl 5

/** いまの端末の状態で、OS がアラーム音を通すか */
fun dndAllowsAlarmsNow(context: Context): Boolean =
    dndAllowsAlarms(readInterruptionFilter(context), readPolicyAllowsAlarms(context))
