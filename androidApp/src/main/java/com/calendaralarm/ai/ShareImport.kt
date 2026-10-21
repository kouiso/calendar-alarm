package com.calendaralarm.ai

import android.content.Intent
import android.os.Bundle
import com.calendaralarm.shared.model.ExtractedEvent

/**
 * 共有シート (ACTION_SEND) で受けた本文を予定の取り込みへ渡す入口 (#102)。
 * iOS の共有拡張「予定に変換」と同じ役割。読み取りは印刷経路と同じ AI 抽出を使う。
 */
object ShareImport {
    /** 取り込みに渡す本文の上限。抽出側 (MailEventExtractor) も 6000 文字で切る */
    private const val MAX_TEXT = 6000
    private const val MAX_TITLE = 60

    /**
     * 画面が新しく開かれた時だけ true。作り直し (退避あり) と履歴からの再開は、
     * 一度受け取って閉じた共有を元の intent から読み直してしまうので false にする
     */
    fun isFreshLaunch(savedInstanceState: Bundle?, intent: Intent?): Boolean =
        savedInstanceState == null &&
            ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0

    /** 共有で届いた件名と本文を1つの本文にする。テキストの共有でなければ null */
    fun textFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("text/") != true) return null
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val joined = listOf(subject, body).filter { it.isNotEmpty() }.distinct().joinToString("\n")
        return joined.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)
    }

    /**
     * AI で読めなかった時の下書き。取り込み画面まで進めて、日時だけ人が入れれば追加できるようにする
     * (黙って何も起きないのが一番困るため)。タイトルは最初の空でない行。
     */
    fun draftFrom(text: String): ExtractedEvent {
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return ExtractedEvent(title = firstLine.take(MAX_TITLE), start = "", description = text)
    }
}
