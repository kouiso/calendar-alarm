package com.calendaralarm.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.calendaralarm.R
import com.calendaralarm.ai.AiConfig
import com.calendaralarm.ai.ShareImport
import com.calendaralarm.shared.ai.MailEventExtractor
import com.calendaralarm.shared.model.ExtractedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 共有で受けた本文を AI で予定に読み取る間の画面 (#102)。
 * 読めた時も読めなかった時も、予定の取り込み画面 (ExtractedEventDialog) へ進める。
 * 読めなかった時は [onResult] の note に理由を入れ、下書きを渡す。
 */
@Composable
fun SharedTextImportDialog(
    text: String,
    onResult: (event: ExtractedEvent, note: String?) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(text) {
        val api = AiConfig.api()
        if (api == null) {
            onResult(ShareImport.draftFrom(text), context.getString(R.string.share_import_ai_unavailable))
            return@LaunchedEffect
        }
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
        val result = withContext(Dispatchers.IO) {
            runCatching { MailEventExtractor(api).extract(text, nowIso, TimeZone.currentSystemDefault()) }
        }
        val ev = result.getOrNull()
        when {
            ev != null -> onResult(ev, null)
            result.isFailure -> onResult(
                ShareImport.draftFrom(text),
                context.getString(
                    R.string.share_import_read_failed,
                    result.exceptionOrNull()?.message ?: context.getString(R.string.share_import_unknown),
                ),
            )
            else -> onResult(ShareImport.draftFrom(text), context.getString(R.string.share_import_no_event))
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.share_import_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.share_import_reading))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.share_import_cancel)) } },
    )
}
