package com.calendaralarm.engine

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.calendaralarm.CalendarAlarmApp
import com.calendaralarm.data.NotificationContentStore
import com.calendaralarm.shared.model.AlarmInstance
import com.calendaralarm.shared.model.AlarmKind
import com.calendaralarm.shared.model.NotificationRuleSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 通知アラームエンジン (元アプリの中核機能のひとつ)。
 * NotificationListenerService で他アプリの通知を監視し、
 * ルール (対象アプリ+キーワード+曜日+時間帯) に合えばアラームを鳴らす。
 * ユーザーがシステム設定で「通知へのアクセス」を許可した時のみ動く。
 */
class NotificationAlarmService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collector: kotlinx.coroutines.Job? = null

    /** 設定の最新スナップショット。通知は高頻度なので毎回 DataStore は読まない。 */
    @Volatile private var enabled = false
    @Volatile private var rules: List<NotificationRuleSpec> = emptyList()

    override fun onListenerConnected() {
        val settings = (application as CalendarAlarmApp).container.settings
        // 再接続のたびに collector が増殖しないよう前回分を止める
        collector?.cancel()
        collector = scope.launch {
            settings.flow.collect { p ->
                enabled = p.notificationAlarmEnabled
                rules = p.notificationRules
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!enabled || rules.isEmpty()) return
        // 自分の通知は絶対拾わない (鳴動通知→再鳴動の無限ループ防止)
        if (sbn.packageName == packageName) return
        // ongoing (進行中表示) とグループサマリーは二重発火の温床なので拾わない
        if (sbn.isOngoing) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val body = "$title $text".ifBlank { return }

        val matched = rules.firstOrNull { it.matches(sbn.packageName, body) } ?: return
        // 同一通知の更新再投稿で連発しないよう、通知キーごとに60秒のクールダウン
        // (マッチ後に適用: 未マッチ通知がクールダウンを消費しないように)。
        // 題か本文が変わった通知は間隔の中でも通す (#384)。内容は等しいかだけを比べ、意味は見ない
        val key = sbn.key
        val now = System.currentTimeMillis()
        val contentKey = NotificationContentStore.keyOf(title, text)
        synchronized(lastFired) {
            if (shouldSkipCooldown(lastFired[key], now, contentKey)) return
            lastFired[key] = CooldownEntry(now, contentKey)
        }
        // 通知キー由来の安定ID: 更新再投稿で同じ通知が連続して鳴らない
        val id = "nf:${key.hashCode().toString(16)}"
        // 鳴動画面の「開く」で元アプリへ飛べるよう PendingIntent を保持
        sbn.notification.contentIntent?.let { pendingOpens[id] = it }
        scope.launch {
            val repo = (application as CalendarAlarmApp).container.repository
            repo.scheduleNotificationAlarm(
                AlarmInstance(
                    id = id,
                    triggerAtMillis = System.currentTimeMillis(),
                    title = title.ifBlank { matched.name },
                    kind = AlarmKind.NOTIFICATION,
                    // 元通知へ飛べるようアプリ名を説明に入れる
                    soundUri = null,
                ),
                contentKey = contentKey,
            )
        }
    }

    companion object {
        /** 通知キー→最後にアラーム化した時刻と内容の印。クールダウン判定用。 */
        private val lastFired = object : LinkedHashMap<String, CooldownEntry>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CooldownEntry>?) =
                size > 256
        }

        /** インスタンスID→元通知の開く PendingIntent。最新16件だけ保持。 */
        val pendingOpens = object : LinkedHashMap<String, android.app.PendingIntent>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.app.PendingIntent>?) =
                size > 16
        }
    }

}

private const val NOTIFICATION_COOLDOWN_MS = 60_000L

/** クールダウン表の1行。最後に鳴動へ渡した時刻と、その時の内容の印 (#384) */
internal data class CooldownEntry(val atMillis: Long, val contentKey: String)

/** 60 秒の間隔の中で、印も同じ時だけ true (落とす)。印が違えば新しい知らせとして通す (#384) */
internal fun shouldSkipCooldown(prev: CooldownEntry?, nowMillis: Long, contentKey: String): Boolean =
    prev != null && nowMillis - prev.atMillis < NOTIFICATION_COOLDOWN_MS && prev.contentKey == contentKey

/** 曜日・時間帯・パッケージ・本文の全条件を評価。internal は「保存したルールが判定に使われる」単体テストのため (#226)。 */
internal fun NotificationRuleSpec.matches(pkg: String, body: String): Boolean {
    if (!packageName.isNullOrBlank() && packageName != pkg) return false
    val cal = Calendar.getInstance()
    if (daysOfWeek.isNotEmpty()) {
        // Calendar: SUNDAY=1..SATURDAY=7 → モデルの 1=月..7=日 に合わせる
        val dow = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> 7
            else -> cal.get(Calendar.DAY_OF_WEEK) - 1
        }
        if (dow !in daysOfWeek) return false
    }
    val start = startMinuteOfDay
    val end = endMinuteOfDay
    if (start != null && end != null && start != end) {
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val inWindow = if (start <= end) now in start until end
            else now >= start || now < end // 深夜跨ぎ (例 22:00-6:00)
        if (!inWindow) return false
    }
    return matchesText(body)
}
