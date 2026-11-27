package com.calendaralarm.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.calendaralarm.BuildConfig
import com.calendaralarm.R
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.shared.logic.DiagnosticReport
import com.calendaralarm.shared.logic.RingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import java.util.Locale

/**
 * 診断の共有 (#286)。鳴動の記録・許可の状態・版・OS・端末の型を1つの文字にまとめて送る。
 * 予定の題は持ち主の決定で必ず伏せる (残す選択肢は作らない)。サーバーには送らない。
 */

/** 共有する文字の材料を集める。DB の読み取りに失敗したら例外のまま返す (呼び出し側が受ける)。 */
internal suspend fun collectDiagnosticInput(
    context: Context,
    repository: AlarmRepository,
    settings: SettingsRepository,
    nowMillis: Long = System.currentTimeMillis(),
): DiagnosticReport.Input {
    val prefs = settings.flow.first()
    val checks = TroubleshootChecks.collect(context, prefs.vibrateWhileRinging)
        .map { DiagnosticReport.Check(it.key, it.state.name) }
    val pending = repository.pendingNow()
    val recentMissed = repository.recentMissedFlow().first()
    val ringStatus = RingStatus.summarize(
        missingPermissions = probePermissions(context).count { !it.value },
        recentMissed = recentMissed,
        hasNextAlarm = pending.isNotEmpty(),
        alarmMuted = !alarmVolumeOk(context),
        allMuted = prefs.muteAll,
    ).kind.name
    val log = repository.recentAuditLog(DiagnosticReport.MAX_LOG_ENTRIES).map {
        DiagnosticReport.LogEntry(
            atMillis = it.atMillis,
            action = it.action,
            detail = it.detail,
            instanceId = it.instanceId,
            triggerAtMillis = it.triggerAtMillis,
            title = it.title,
        )
    }
    // 伏せる題: 記録の title、PENDING の予約の題、時刻のアラームのラベル
    val knownTitles = (
        log.mapNotNull { it.title } +
            pending.map { it.title } +
            repository.standaloneAlarmsFlow().first().map { it.label }
        ).filter { it.isNotBlank() }.distinct()
    return DiagnosticReport.Input(
        platform = "android",
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        locale = Locale.getDefault().toLanguageTag(),
        zoneId = TimeZone.currentSystemDefault().id,
        generatedAtMillis = nowMillis,
        muteAll = prefs.muteAll,
        ringStatus = ringStatus,
        nextAlarmAtMillis = pending.minOfOrNull { it.triggerAtMillis },
        checks = checks,
        log = log,
        knownTitles = knownTitles,
    )
}

/** 文字を共有する選ぶ画面の Intent。 */
internal fun diagnosticShareIntent(context: Context, text: String): Intent {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_log_subject))
    return Intent.createChooser(send, context.getString(R.string.share_log_title))
}

/** 診断の画面の最後に置く「記録を共有」の1行。 */
@Composable
internal fun ShareLogRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("share_log_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Share, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.share_log_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.share_log_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 送る文字の全文を見せ、「共有する」で選ぶ画面を開く。題は必ず伏せるので切り替えは無い。 */
@Composable
internal fun ShareLogDialog(
    repository: AlarmRepository,
    settings: SettingsRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val built = DiagnosticReport.build(collectDiagnosticInput(context, repository, settings))
            text = built
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_log_title)) },
        text = {
            val body = text
            when {
                failed -> Text(stringResource(R.string.share_log_read_failed), modifier = Modifier.testTag("share_log_failed"))
                body == null -> Text(stringResource(R.string.share_log_loading))
                else -> Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("share_log_body"),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text != null && !failed,
                onClick = {
                    val body = text ?: return@TextButton
                    try {
                        context.startActivity(diagnosticShareIntent(context, body))
                    } catch (e: ActivityNotFoundException) {
                        // 共有先のアプリが無い時は何もしない
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.share_log_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
