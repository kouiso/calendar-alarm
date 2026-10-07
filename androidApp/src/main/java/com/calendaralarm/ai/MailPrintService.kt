package com.calendaralarm.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import androidx.core.app.NotificationCompat
import com.calendaralarm.CalendarAlarmApp
import com.calendaralarm.MainActivity
import com.calendaralarm.shared.ai.MailEventExtractor
import com.calendaralarm.shared.ai.OpenRouterApi
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「印刷→予定化」サービス (元アプリの PrintService に相当)。
 * Gmail等で「印刷」を選ぶと PDF がここへ届く。
 * PDF のテキストを OpenRouter で予定情報に変換し、確認用通知を出す。
 * ジョブそのものは印刷しない (プリンタではなく取込口として使う)。
 */
class MailPrintService : PrintService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 実プリンタは存在しない (取込口としての利用) ので発見セッションは不要。 */
    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession? = null

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        // ユーザー側のキャンセル要求。非同期処理が走っていても即 cancel で返す
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val info = printJob.info ?: return
        printJob.start()
        scope.launch {
            val tag = info.label ?: "印刷ジョブ"
            try {
                val text = extractText(printJob)
                val settings = (application as CalendarAlarmApp).container.settings
                val prefs = settings.flow.first()
                val key = prefs.openRouterApiKey
                if (key.isNullOrBlank()) {
                    notifyExtract("AIキー未設定: 設定画面で OpenRouter のキーを入れてください", null)
                    printJob.complete()
                    return@launch
                }
                if (text.isNullOrBlank()) {
                    notifyExtract("$tag: テキストを読み取れませんでした", null)
                    printJob.complete()
                    return@launch
                }
                val api = OpenRouterApi(apiKey = key, model = prefs.openRouterModel)
                val nowIso = SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US,
                ).format(Date())
                val ev = MailEventExtractor(api)
                    .extract(text, nowIso, TimeZone.currentSystemDefault())
                if (ev == null) {
                    notifyExtract("$tag: 予定が見つかりませんでした", null)
                } else {
                    notifyExtract("${ev.title} を予定に追加", json.encodeToString(ev))
                }
                printJob.complete()
            } catch (e: Exception) {
                notifyExtract("$tag: ${e.message ?: "失敗"}", null)
                printJob.fail("extraction failed")
            }
        }
    }

    /** PDF をテキスト化 (PDFBox)。本文が取れなければ null。 */
    private fun extractText(printJob: PrintJob): String? {
        val pfd: ParcelFileDescriptor = printJob.document.data ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            runCatching {
                PDDocument.load(input).use { doc ->
                    PDFTextStripper().getText(doc)
                }
            }.getOrNull()
        }
    }

    private fun notifyExtract(text: String, eventJson: String?) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "メール→予定", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (eventJson != null) putExtra(MainActivity.EXTRA_EXTRACTED_EVENT, eventJson)
        }
        val pi = PendingIntent.getActivity(
            this, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mgr.notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("メール→予定")
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "mail_extract_v1"
    }
}
