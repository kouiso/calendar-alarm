package com.calendaralarm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.calendaralarm.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.shared.logic.AlarmExpander
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * アプリを開いた時に、足りない許可を1つずつ自動で頼む (局長決定 2026-10-17 の1)。
 * 1画面に1つだけ出し、残りの数は数えない。設定から戻っても未許可なら一度だけ
 * 「まだオフのまま」で頼み直し、「あとで」を押されたらこの起動中はもう頼まない。
 * 断られた後は設定の「権限」画面 ([PermissionsScreen]) から全部許可・個別に直せる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionAsker(repository: AlarmRepository, onGranted: (AppPermission) -> Unit = {}) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    var granted by remember { mutableStateOf(probePermissions(context)) }
    // 回転やプロセス再生成で同じ許可を頼み直さないよう保存する
    var stopped by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf<String?>(null) }
    var retried by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // 頼み直しの結果待ち。OS のダイアログや設定画面から戻るまでは「断られた」と決めない
    var awaitingRetry by rememberSaveable { mutableStateOf<String?>(null) }
    var askedAny by rememberSaveable { mutableStateOf(false) }
    var readyClosed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 新たに付いた許可の通知は ON_RESUME の差分だけで出す (ダイアログ結果と二重に出さない)
    var seen by remember { mutableStateOf(granted) }
    // 頼み直しから戻った時点で、まだオフなら断られたとして記録する
    fun settleRetry(now: Map<AppPermission, Boolean>) {
        val a = awaitingRetry ?: return
        awaitingRetry = null
        if (now[AppPermission.entries.first { it.name == a }] == false) retried = retried + a
    }
    LaunchedEffect(tick) {
        val now = probePermissions(context)
        AppPermission.entries.filter { seen[it] == false && now[it] == true }.forEach(onGranted)
        seen = now
        granted = now
        settleRetry(now)
    }
    val requester = rememberPermissionRequester { _, _ ->
        granted = probePermissions(context)
        settleRetry(granted)
    }
    if (stopped) return

    val askedPerm = asked?.let { a -> AppPermission.entries.firstOrNull { it.name == a } }
    val retry = askedPerm != null && granted[askedPerm] == false
    if (retry && askedPerm!!.name in retried) {
        // 頼み直しても戻ってきた = 断られた。ここで止めて権限画面に任せる
        stopped = true
        return
    }
    val current = if (retry) askedPerm else PermissionPlan.next(granted, emptySet())

    if (current == null) {
        if (askedAny && !readyClosed) {
            ModalBottomSheet(onDismissRequest = { readyClosed = true }) {
                AskSheetBody(
                    title = stringResource(R.string.ask_ready_title),
                    body = stringResource(R.string.ask_ready_body),
                    cta = stringResource(R.string.ask_ready_test),
                    later = stringResource(R.string.common_close),
                    onCta = {
                        readyClosed = true
                        scope.launch {
                            repository.scheduleAdhoc(
                                AlarmExpander.timerInstance(10_000L, Clock.System.now(), label = "テスト鳴動"),
                            )
                        }
                    },
                    onLater = { readyClosed = true },
                )
            }
        }
        return
    }

    ModalBottomSheet(onDismissRequest = { stopped = true }) {
        AskSheetBody(
            title = stringResource(if (retry) R.string.ask_still_off else current.askTitle),
            body = if (retry) {
                stringResource(R.string.ask_retry_body, stringResource(current.label), stringResource(current.reason))
            } else {
                stringResource(current.askBody)
            },
            cta = stringResource(
                when {
                    retry -> R.string.ask_open_settings_again
                    current.viaSettings -> R.string.ask_turn_on_in_settings
                    else -> R.string.ask_allow
                },
            ),
            later = stringResource(R.string.ask_later),
            onCta = {
                if (retry) awaitingRetry = current.name
                asked = current.name
                askedAny = true
                requester.request(current)
            },
            onLater = { stopped = true },
        )
    }
}

@Composable
private fun AskSheetBody(
    title: String,
    body: String,
    cta: String,
    later: String,
    onCta: () -> Unit,
    onLater: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onCta, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text(cta, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) { Text(later) }
    }
}
