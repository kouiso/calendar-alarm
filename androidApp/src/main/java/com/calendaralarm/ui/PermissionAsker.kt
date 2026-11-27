package com.calendaralarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.calendaralarm.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.shared.logic.AlarmExpander
import com.calendaralarm.ui.theme.NightButton
import com.calendaralarm.ui.theme.NightIconRings
import com.calendaralarm.ui.theme.NightLinkButton
import com.calendaralarm.ui.theme.NightModalSheet
import com.calendaralarm.ui.theme.NightTokens
import com.calendaralarm.ui.theme.StatusColors
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * アプリを開いた時に、足りない許可を1つずつ自動で頼む (局長決定 2026-10-17 の1)。
 * 1画面に1つだけ出し、残りの数は数えない。設定から戻っても未許可なら一度だけ
 * 「まだオフのまま」で頼み直し、「あとで」を押されたらこの起動中はもう頼まない。
 * 断られた後は設定の「権限」画面 ([PermissionsScreen]) から全部許可・個別に直せる。
 */
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
    val testRingLabel = stringResource(R.string.ask_test_ring_label)

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
            NightModalSheet(onDismissRequest = { readyClosed = true }) {
                AskSheetBody(
                    title = stringResource(R.string.ask_ready_title),
                    body = stringResource(R.string.ask_ready_body),
                    cta = stringResource(R.string.ask_ready_test),
                    later = stringResource(R.string.common_close),
                    icon = ASK_READY_ICON,
                    tone = askIconTone(retry = false, ready = true),
                    onCta = {
                        readyClosed = true
                        scope.launch {
                            repository.scheduleAdhoc(
                                AlarmExpander.timerInstance(10_000L, Clock.System.now(), label = testRingLabel),
                            )
                        }
                    },
                    onLater = { readyClosed = true },
                )
            }
        }
        return
    }

    NightModalSheet(onDismissRequest = { stopped = true }) {
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
            icon = current.askIcon(),
            tone = askIconTone(retry = retry, ready = false),
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

/** 許可を頼む面の印の色。ふだんは ACCENT、頼み直しは WARN、準備OK は OK */
internal enum class AskIconTone { ACCENT, WARN, OK }

internal fun askIconTone(retry: Boolean, ready: Boolean): AskIconTone = when {
    ready -> AskIconTone.OK
    retry -> AskIconTone.WARN
    else -> AskIconTone.ACCENT
}

/** 板 d68/ask.dc.html の各列の絵 */
internal fun AppPermission.askIcon(): ImageVector = when (this) {
    AppPermission.CALENDAR -> Icons.Outlined.CalendarToday
    AppPermission.NOTIFY -> Icons.Outlined.Notifications
    AppPermission.EXACT -> Icons.Outlined.Schedule
    AppPermission.FULL_SCREEN -> Icons.Outlined.Smartphone
}

internal val ASK_READY_ICON: ImageVector = Icons.Default.Check

internal const val ASK_ICON_TAG = "ask_icon"
internal const val ASK_TITLE_TAG = "ask_title"
internal const val ASK_BODY_TAG = "ask_body"
internal const val ASK_CTA_TAG = "ask_cta"
internal const val ASK_LATER_TAG = "ask_later"

@Composable
internal fun AskSheetBody(
    title: String,
    body: String,
    cta: String,
    later: String,
    icon: ImageVector,
    tone: AskIconTone,
    onCta: () -> Unit,
    onLater: () -> Unit,
) {
    val ringColor = when (tone) {
        AskIconTone.ACCENT -> NightTokens.accentRing
        AskIconTone.WARN -> NightTokens.warnRing
        AskIconTone.OK -> NightTokens.okRing
    }
    val iconTint = when (tone) {
        AskIconTone.ACCENT -> MaterialTheme.colorScheme.primary
        AskIconTone.WARN -> StatusColors.warn
        AskIconTone.OK -> StatusColors.ok
    }
    // 上の 2 は、つまみの下 16 を板の 18 (下 6 と間 12) に足すため。印の左の 1 は外の余白 9 を板の左 10 に足すため
    Column(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NightIconRings(icon, ringColor, iconTint, Modifier.padding(start = 1.dp).testTag(ASK_ICON_TAG))
        Text(
            title,
            modifier = Modifier.testTag(ASK_TITLE_TAG),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 21.sp, lineHeight = 30.45.sp, fontWeight = FontWeight.SemiBold,
        )
        Text(
            body,
            modifier = Modifier.testTag(ASK_BODY_TAG),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp, lineHeight = 23.1.sp,
        )
        NightButton(cta, onCta, Modifier.fillMaxWidth().testTag(ASK_CTA_TAG))
        NightLinkButton(later, onLater, Modifier.testTag(ASK_LATER_TAG))
    }
}
