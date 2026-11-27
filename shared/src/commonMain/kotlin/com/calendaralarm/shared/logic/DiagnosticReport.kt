package com.calendaralarm.shared.logic

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/**
 * 診断の画面から共有する「記録と端末の状態」の文字を作る (#286)。
 * Android と iOS で同じ形にするため shared に置く。予定の題は持ち主の決定で必ず伏せる (残す選択肢は作らない)。
 * 題を書く種類の記録は [TITLE_IN_DETAIL] の表で決まっており、題がもう無い予定の記録でも漏れない。
 */
object DiagnosticReport {
    const val FORMAT_VERSION = 1
    const val MAX_LOG_ENTRIES = 300
    const val MASK = "[title]"

    /**
     * 記録の detail に予定の題を書く種類 (OS ごと)。ここに入る種類は detail を [MASK] と後ろの決まった書式だけにする。
     * 記録を書く所 (AlarmRepository.audit など) で detail に題を書く種類を足したら、ここにも足すこと
     */
    val TITLE_IN_DETAIL: Map<String, Set<String>> = mapOf(
        "android" to setOf("ALARM_OFF"),
        "ios" to setOf(
            "DISMISS", "DISMISS_OFFAPP", "FIRED", "FIRED_UNCONFIRMED", "MISSED",
            "NOTIFY_SCHED", "CAPACITY_SKIP", "ALARM_OFF", "NOTIFY",
        ),
    )

    // 通知 は iOS の記録の書式。shared の文字列リテラルを増やさないため Unicode のエスケープで書く
    /** 題の後ろに付く決まった書式 (` @M/d HH:mm` と任意の ` via=…`、または ` (alerting)`・` (通知)`・` (id=数字)`)。 */
    val TITLE_DETAIL_TAIL: Regex = Regex("""( @\d{1,2}/\d{1,2} \d{2}:\d{2}( via=\S+)?| \((alerting|\u901A\u77E5|id=\d+)\))$""")

    private val LINE_BREAK = Regex("""\r\n|\r|\n""")

    @Serializable
    data class Check(val key: String, val state: String)

    @Serializable
    data class LogEntry(
        val atMillis: Long,
        val action: String,
        val detail: String,
        val instanceId: String? = null,
        val triggerAtMillis: Long? = null,
        val title: String? = null,
    )

    @Serializable
    data class Input(
        /** `android` または `ios` */
        val platform: String,
        val appVersion: String,
        val osVersion: String,
        val deviceModel: String,
        val locale: String,
        val zoneId: String,
        val generatedAtMillis: Long,
        val muteAll: Boolean,
        /** `RingStatus.Kind` の名前 */
        val ringStatus: String,
        val nextAlarmAtMillis: Long? = null,
        val checks: List<Check> = emptyList(),
        val log: List<LogEntry> = emptyList(),
        /** detail の中から [MASK] へ置き換える題 (記録自身の title も自動で含める) */
        val knownTitles: List<String> = emptyList(),
    )

    fun build(input: Input): String {
        val zone = TimeZone.of(input.zoneId)
        // 長い題から先に置き換える (別の題の一部になっている題が先に消えて、長い題が残るのを避ける)
        val titles = (input.knownTitles + input.log.mapNotNull { it.title })
            .flatMap { listOf(it, oneLine(it)) }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
        val entries = input.log.sortedByDescending { it.atMillis }.take(MAX_LOG_ENTRIES)
        val inDetail = TITLE_IN_DETAIL[input.platform].orEmpty()

        val lines = mutableListOf(
            "calendar-alarm diagnostic report v$FORMAT_VERSION",
            "generated: ${format(input.generatedAtMillis, zone)}",
            "platform: ${input.platform}",
            "app: ${input.appVersion}",
            "os: ${input.osVersion}",
            "device: ${input.deviceModel}",
            "locale: ${input.locale}",
            "titles: hidden",
            "mute_all: ${input.muteAll}",
            "ring_status: ${input.ringStatus}",
            "next_alarm: ${input.nextAlarmAtMillis?.let { format(it, zone) } ?: "none"}",
            "checks:",
        )
        input.checks.forEach { lines += "- ${it.key}: ${it.state}" }
        lines += "log: ${entries.size} entries (newest first)"
        for (e in entries) {
            val sb = StringBuilder("${format(e.atMillis, zone)} ${e.action}")
            e.instanceId?.let { sb.append(" id=").append(it) }
            e.triggerAtMillis?.let { sb.append(" trigger=").append(format(it, zone)) }
            if (e.title != null) sb.append(" title=").append(MASK)
            val detail = oneLine(e.detail)
            val shown = if (e.action in inDetail) {
                maskTitleDetail(detail)
            } else {
                titles.fold(detail) { acc, t -> acc.replace(t, MASK) }
            }
            sb.append(" | ").append(shown)
            lines += sb.toString()
        }
        return lines.joinToString("\n")
    }

    /** 題を書く種類の detail を [MASK] と後ろの決まった書式だけにする。書式に合わなければ [MASK] だけ。 */
    internal fun maskTitleDetail(detail: String): String =
        MASK + (TITLE_DETAIL_TAIL.find(detail)?.value ?: "")

    private fun oneLine(s: String): String = s.replace(LINE_BREAK, " ")

    /** `yyyy-MM-dd HH:mm:ss`。LocalDateTime.toString() は秒が0の時に省くので自前で0詰めする */
    private fun format(millis: Long, zone: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
        fun p(n: Int, w: Int = 2) = n.toString().padStart(w, '0')
        return "${p(t.year, 4)}-${p(t.monthNumber)}-${p(t.dayOfMonth)} ${p(t.hour)}:${p(t.minute)}:${p(t.second)}"
    }
}
