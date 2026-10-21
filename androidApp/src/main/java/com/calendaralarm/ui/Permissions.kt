package com.calendaralarm.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.calendaralarm.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * アプリが求める許可。並び順がそのまま「1つずつ頼む」順になる。
 * 「他のアプリの上に重ねて表示」は求めない (局長決定 2026-10-17)。
 */
enum class AppPermission(
    @StringRes val label: Int,
    @StringRes val reason: Int,
    @StringRes val askTitle: Int,
    @StringRes val askBody: Int,
    /** OS のダイアログでなく設定画面で切り替える許可か。 */
    val viaSettings: Boolean,
) {
    CALENDAR(
        R.string.perm_calendar_label, R.string.perm_calendar_reason,
        R.string.perm_calendar_ask_title, R.string.perm_calendar_ask_body,
        viaSettings = false,
    ),
    NOTIFY(
        R.string.perm_notify_label, R.string.perm_notify_reason,
        R.string.perm_notify_ask_title, R.string.perm_notify_ask_body,
        viaSettings = false,
    ),
    EXACT(
        R.string.perm_exact_label, R.string.perm_exact_reason,
        R.string.perm_exact_ask_title, R.string.perm_exact_ask_body,
        viaSettings = true,
    ),
    FULL_SCREEN(
        R.string.perm_fullscreen_label, R.string.perm_fullscreen_reason,
        R.string.perm_fullscreen_ask_title, R.string.perm_fullscreen_ask_body,
        viaSettings = true,
    ),
}

/** 端末の今の許可状態。OS 版で権限制でないものは許可済みとして扱う。 */
fun probePermissions(context: Context): Map<AppPermission, Boolean> = mapOf(
    AppPermission.CALENDAR to (
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        ),
    // 33 未満には実行時の許可が無いが、設定で通知を切られていることはある。鳴動側の判定と同じく有効かを見る
    AppPermission.NOTIFY to (
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                )
        ),
    AppPermission.EXACT to (
        Build.VERSION.SDK_INT < 31 ||
            (context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: true)
        ),
    AppPermission.FULL_SCREEN to (
        Build.VERSION.SDK_INT < 34 ||
            (context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true)
        ),
)

/** 「1つずつ頼む」の進め方。画面から切り離して単体テストする。 */
object PermissionPlan {
    /** 次に頼む許可。断られたものは同じ流れの中で二度頼まない。 */
    fun next(granted: Map<AppPermission, Boolean>, declined: Set<AppPermission>): AppPermission? =
        AppPermission.entries.firstOrNull { granted[it] == false && it !in declined }

    fun allGranted(granted: Map<AppPermission, Boolean>): Boolean =
        AppPermission.entries.all { granted[it] == true }
}

/** 設定画面で切り替える許可の、端末の設定画面を直接開く。 */
private fun openSettingsFor(activity: Activity, p: AppPermission) {
    val pkg = Uri.parse("package:${activity.packageName}")
    val intent = when (p) {
        AppPermission.EXACT ->
            if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
        AppPermission.FULL_SCREEN ->
            if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null
        AppPermission.NOTIFY ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        AppPermission.CALENDAR -> null
    } ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    // 一部端末 (Android Go 等) は個別の設定画面を持たないのでアプリ詳細へ倒す
    runCatching { activity.startActivity(intent) }.onFailure {
        runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
    }
}

private fun runtimePermissionOf(p: AppPermission): String? = when (p) {
    AppPermission.CALENDAR -> Manifest.permission.READ_CALENDAR
    AppPermission.NOTIFY -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
    else -> null
}

/** ON_RESUME で増える tick。設定アプリから戻った時に許可を読み直す。 */
@Composable
internal fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

/**
 * 許可を1つ頼む仕組み。OS のダイアログで頼めるものはダイアログ、残りは設定画面を開く。
 * 「今後表示しない」を選ばれた後はダイアログが出ないので、設定画面へ倒す。
 * 結果は [onResult] (ダイアログ) か、戻った時の ON_RESUME での読み直しで分かる。
 */
internal class PermissionRequester(
    private val activity: Activity?,
    private val launch: (String) -> Unit,
) {
    fun request(p: AppPermission) {
        val a = activity ?: return
        val runtime = runtimePermissionOf(p)
        if (runtime != null && !p.viaSettings) launch(runtime) else openSettingsFor(a, p)
    }
}

@Composable
internal fun rememberPermissionRequester(onResult: (AppPermission, Boolean) -> Unit): PermissionRequester {
    val context = LocalContext.current
    val activity = context as? Activity
    var pending by remember { mutableStateOf<AppPermission?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending ?: return@rememberLauncherForActivityResult
        pending = null
        val perm = runtimePermissionOf(p)
        if (!ok && activity != null && perm != null && !activity.shouldShowRequestPermissionRationale(perm)) {
            // 二度目以降の拒否や「今後表示しない」はダイアログが無言で閉じるだけなので、設定を開く
            openSettingsFor(activity, p)
        }
        onResult(p, ok)
    }
    return remember(activity) {
        PermissionRequester(activity) { perm ->
            pending = AppPermission.entries.firstOrNull { runtimePermissionOf(it) == perm }
            launcher.launch(perm)
        }
    }
}

/**
 * 権限画面 (デザイン ⑨)。断られた後に「全部許可する」と項目ごとのスイッチで直す
 * (局長決定 2026-10-17 の2)。押すと端末のその設定が直接開き、戻ると状態が自動で変わる。
 */
@Composable
fun PermissionsScreen(onBack: () -> Unit, onGranted: (AppPermission) -> Unit = {}) {
    val context = LocalContext.current
    val activity = context as? Activity
    val tick = rememberResumeTick()
    var granted by remember { mutableStateOf(probePermissions(context)) }
    // 「全部許可する」の連続依頼中か。戻っても未許可なら断られたとみなして止める
    var chain by rememberSaveable { mutableStateOf(false) }
    var chainAsked by rememberSaveable { mutableStateOf<String?>(null) }
    var requestNext: (() -> Unit)? = null
    // 次へ進む判断は ON_RESUME だけで行う。ダイアログの結果でも進めると、直後の
    // ON_RESUME と二重に進んで設定画面を2つ開いたり、誤って断られた扱いにしたりする
    val requester = rememberPermissionRequester { _, _ -> granted = probePermissions(context) }
    requestNext = {
        if (chain) {
            val askedPerm = chainAsked?.let { a -> AppPermission.entries.firstOrNull { it.name == a } }
            if (askedPerm != null && granted[askedPerm] == false) {
                chain = false
            } else {
                val next = PermissionPlan.next(granted, emptySet())
                if (next == null) chain = false else { chainAsked = next.name; requester.request(next) }
            }
        }
    }
    LaunchedEffect(tick) {
        val now = probePermissions(context)
        // 新たに付いた許可を知らせる。カレンダーなら呼び出し側が予約を作り直す (待たせると次の定期同期まで鳴らない)
        AppPermission.entries.filter { granted[it] == false && now[it] == true }.forEach(onGranted)
        granted = now
        requestNext?.invoke()
    }

    val offCount = AppPermission.entries.count { granted[it] == false }
    val allOn = offCount == 0
    val ok = MaterialTheme.colorScheme.primary
    val warn = MaterialTheme.colorScheme.error

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 0.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Text(stringResource(R.string.perm_screen_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val c = if (allOn) ok else warn
                    Box(
                        Modifier.size(36.dp).background(c.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (allOn) Icons.Default.Check else Icons.Default.PriorityHigh,
                            contentDescription = null, tint = c, modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(
                                when {
                                    allOn -> R.string.perm_summary_all_ok
                                    offCount == AppPermission.entries.size -> R.string.perm_summary_none
                                    else -> R.string.perm_summary_some_missing
                                },
                            ),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(if (allOn) R.string.perm_summary_all_granted else R.string.perm_summary_open_in_order),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!allOn) {
                    Button(
                        onClick = {
                            chain = true
                            chainAsked = null
                            requestNext?.invoke()
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(stringResource(R.string.perm_grant_all), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            AppPermission.entries.forEachIndexed { i, p ->
                val on = granted[p] == true
                val stateText = stringResource(if (on) R.string.perm_state_granted else R.string.perm_state_missing)
                val label = stringResource(p.label)
                val openDescription = stringResource(R.string.perm_open_settings_for, label, stateText)
                // 許可済みでも押せば OS の設定を開き、そこでオフにできる (アプリからは外せないため)
                val open = {
                    chain = false
                    // 許可済みの実行時権限を頼み直しても OS は何も出さない。外したい時に備えて端末の設定を開く
                    // 通知は権限があっても設定で切られていることがあり、その時も頼み直しでは何も出ない
                    val notifyBlockedInSettings = p == AppPermission.NOTIFY && !on && (
                        Build.VERSION.SDK_INT < 33 ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                        )
                    if ((on || notifyBlockedInSettings) && activity != null) openSettingsFor(activity, p) else requester.request(p)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp)
                        .clickable(onClick = open)
                        .semantics { contentDescription = openDescription }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(p.reason), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stateText, style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold, color = if (on) ok else warn,
                        )
                    }
                    Switch(checked = on, onCheckedChange = { open() })
                }
                if (i < AppPermission.entries.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
        Text(
            stringResource(R.string.perm_screen_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(96.dp).background(Color.Transparent))
    }
}
