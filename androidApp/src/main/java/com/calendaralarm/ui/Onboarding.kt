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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.shared.logic.AlarmExpander
import kotlinx.datetime.Clock

/**
 * 初回導線の権限スナップショット。ON_RESUME ごとに取り直して
 * 設定アプリで変更された状態を拾う (以前は初回1回だけ計算して
 * 戻ってきても古いままだった)。
 */
internal data class PermSnapshot(
    val calendarOk: Boolean,
    val notifOk: Boolean,
    val exactOk: Boolean,
    val fsiOk: Boolean,
    val overlayOk: Boolean,
) {
    /** 鳴らす側の許可 (通知 + 正確な時刻)。 */
    val ringOk get() = notifOk && exactOk

    /** 止める側の許可 (全画面通知 + 他アプリの上に表示)。FSI だけでは
     *  停止UI が出ない端末があるため両方を要求する。 */
    val stopOk get() = fsiOk && overlayOk
    val allOk get() = ringOk && stopOk
    val missingRingCount get() = listOf(notifOk, exactOk).count { !it }
    val missingStopCount get() = listOf(fsiOk, overlayOk).count { !it }
}

internal fun computePermSnapshot(context: Context): PermSnapshot {
    val calendarOk = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED
    val notifOk = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    // canScheduleExactAlarms() は API 31+。それ未満は正確アラームが常時許可なので true。
    val exactOk = Build.VERSION.SDK_INT < 31 ||
        (context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: true)
    // フルスクリーン Intent は API 34+ でサイドロード版は既定拒否。
    // 未許可だと鳴動画面が出ず「止められない」状態になり得る。
    val fsiOk = Build.VERSION.SDK_INT < 34 ||
        (context.getSystemService(NotificationManager::class.java)
            ?.canUseFullScreenIntent() ?: true)
    // SYSTEM_ALERT_WINDOW は FSI が効かない環境でも停止UIを必ず出せる保険。
    val overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
    return PermSnapshot(calendarOk, notifOk, exactOk, fsiOk, overlayOk)
}

/** ON_RESUME のたびにインクリメントする tick。権限の再評価トリガ。 */
@Composable
internal fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalContext.current as? LifecycleOwner
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) tick++
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    return tick
}

/** 権限スナップショット。ON_RESUME で自動再評価し、ランチャー結果でも書き換えられる。 */
@Composable
internal fun rememberPermSnapshot(context: Context): MutableState<PermSnapshot> {
    val state = remember { mutableStateOf(computePermSnapshot(context)) }
    val tick = rememberResumeTick()
    LaunchedEffect(tick) { state.value = computePermSnapshot(context) }
    return state
}

/** 「今後表示しない」済みだと権限ダイアログ自体が出ないためアプリ詳細設定を開く。 */
internal fun openAppDetails(activity: Activity) {
    runCatching {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            },
        )
    }
}

/** 10秒後の鳴動テスト。設定のヘルスカードと初回シートで共有する。 */
internal suspend fun scheduleRingTest(repository: AlarmRepository) {
    repository.scheduleAdhoc(
        AlarmExpander.timerInstance(
            durationMillis = 10_000L,
            now = Clock.System.now(),
        ).copy(title = "テスト鳴動"),
    )
}

/**
 * 予定画面トップの「カレンダーをつなぐ」カード。未接続の間だけ出す。
 * READ_CALENDAR だけを要求し、タイマー/単発アラームは接続不要であることを併記する。
 */
@Composable
internal fun CalendarConnectCard(
    perms: MutableState<PermSnapshot>,
    onConnected: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        perms.value = computePermSnapshot(context)
        if (granted) {
            onConnected()
        } else if (activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
        ) {
            // 「今後表示しない」選択済みだと launch() が無言で終わるだけなので設定へ
            openAppDetails(activity)
        }
    }
    if (perms.value.calendarOk) return

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "予定に合わせて、前もって鳴らす",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "カレンダーをつなぐと、予定の前に自動でアラームが入る。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { launcher.launch(Manifest.permission.READ_CALENDAR) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("カレンダーをつなぐ") }
            Spacer(Modifier.height(8.dp))
            Text(
                "タイマーと単発のアラームは、つながなくても使える。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 鳴動系の許可が欠けている間だけ予定画面の上に出る細い警告カード。 */
@Composable
internal fun PermissionNagCard(
    perms: PermSnapshot,
    onFix: () -> Unit,
) {
    val (off, effect) = when {
        !perms.notifOk -> "通知がオフ" to "鳴っても、画面に出ないことがある"
        !perms.exactOk -> "正確な時刻がオフ" to "鳴る時刻がずれることがある"
        !perms.fsiOk -> "全画面表示がオフ" to "鳴っても、止めるボタンが小さくなる"
        !perms.overlayOk -> "ほかのアプリの上の表示がオフ" to "止める画面が出ないことがある"
        else -> return
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    off,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    effect,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onFix) { Text("直す") }
        }
    }
}

/**
 * 権限シートの見出し。リード分が分かる時 (予定の鳴動) だけ「N分前」を出し、
 * 単発アラームのように開始時刻ちょうどで鳴るものには分数を付けない。
 */
internal fun ringSetupTitle(subject: String?, leadMinutes: Int?): String {
    val name = subject?.takeIf { it.isNotBlank() }
    return if (leadMinutes != null && leadMinutes > 0) {
        "${name ?: "予定"}の${leadMinutes}分前に、きちんと鳴らすために"
    } else {
        "${name ?: "アラーム"}を、きちんと鳴らすために"
    }
}

/** 権限シートの段。鳴らす側が欠けていれば1、止める側が欠けていれば2、全部揃えば3。 */
internal fun ringSetupStage(p: PermSnapshot): Int = when {
    !p.ringOk -> 1
    !p.stopOk -> 2
    else -> 3
}

/**
 * 設定画面を開く。端末に該当画面が無い (Android Go はオーバーレイ設定を持たない等) と
 * ActivityNotFoundException になるため、開けなければアプリ詳細設定に落とす。
 */
internal fun openSettingsOrAppDetails(activity: Activity, action: String) {
    val opened = runCatching {
        activity.startActivity(
            Intent(action).apply { data = Uri.parse("package:${activity.packageName}") },
        )
    }.isSuccess
    if (!opened) openAppDetails(activity)
}

/**
 * 初めてアラームをオンにした時の権限シート。段階は権限状態から導出するため、
 * 設定アプリから戻った時点の最新状態に自動で追従する (ON_RESUME 再評価)。
 * 段1=鳴らす許可 (通知/正確な時刻)、段2=止める許可 (全画面/他アプリの上)、段3=完了+テスト。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RingSetupSheet(
    subject: String?,
    leadMinutes: Int?,
    firstTime: Boolean,
    onRingTest: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val perms = rememberPermSnapshot(context)
    val p = perms.value
    val stage = ringSetupStage(p)

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        perms.value = computePermSnapshot(context)
        when {
            !granted && activity != null &&
                !activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS,
                ) -> {
                // 恒久拒否ではダイアログが出ないので設定画面を開く
                openAppDetails(activity)
            }
            perms.value.notifOk && !perms.value.exactOk && activity != null -> {
                // 通知が通ったら「正確な時刻」も同じ流れで続けて取る
                openSettingsOrAppDetails(activity, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 30.dp),
        ) {
            when (stage) {
                1 -> {
                    if (firstTime) {
                        Text(
                            "最初の1回だけ",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        ringSetupTitle(subject, leadMinutes),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "鳴らすのに${p.missingRingCount}つ許可がいる。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    PermRow("通知", p.notifOk)
                    PermRow("正確な時刻", p.exactOk)
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            when {
                                activity == null -> Unit
                                !p.notifOk ->
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                !p.exactOk -> openSettingsOrAppDetails(
                                    activity, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("許可する") }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("あとで") }
                }
                2 -> {
                    Text(
                        "あと${p.missingStopCount}つ",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "鳴った時に、すぐ止められるように",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(14.dp))
                    PermRow("全画面で表示", p.fsiOk)
                    PermRow("ほかのアプリの上に表示", p.overlayOk)
                    if (Build.VERSION.SDK_INT >= 33) {
                        // Android13+ のサイドロード版は「制限された設定」で
                        // 特殊アクセスのスイッチがグレーアウトする
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "スイッチが押せない時は、アプリ情報の右上「︙」→「制限された設定を許可」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            when {
                                activity == null -> Unit
                                !p.fsiOk -> openSettingsOrAppDetails(
                                    activity, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                )
                                !p.overlayOk -> openSettingsOrAppDetails(
                                    activity, Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("設定でオンにする") }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "設定が開く。オンにして戻ると、自動で進む。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 端末側にこの設定が無い (Android Go 等) と段2を抜けられないため出口を置く
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("あとで") }
                }
                else -> {
                    Text(
                        "これで、鳴ってすぐ止められる",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            // 予約はシートより長く生きる scope (MainScaffold) で行う。
                            // シート側の scope だと閉じた瞬間にキャンセルされ、連打で二重にも鳴る
                            onRingTest()
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("テストで鳴らして、止めてみる") }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("閉じる") }
                }
            }
        }
    }
}

@Composable
private fun PermRow(label: String, granted: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).background(
                if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "許可済み",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!granted) {
            Text(
                "未許可",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
