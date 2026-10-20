package com.calendaralarm.ui

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.engine.AlarmService
import kotlinx.coroutines.flow.first

/**
 * 1項目の検査結果。OK=満たす / NG=要対応 (設定へ飛べる) / NA=この OS では不要 /
 * INFO=満たさなくても鳴るが、端末によっては遅れ得るので知らせるだけの参考項目。
 */
enum class CheckState { OK, NG, NA, INFO }

data class TroubleshootItem(
    val key: String,
    val state: CheckState,
    val title: String,
    val description: String,
    /** NG の時に開く設定画面。null なら飛び先なし。 */
    val fix: Intent?,
)

/**
 * 「鳴らない・止まらない」の原因になる端末側の設定を一か所で検査する。
 * 画面から切り離して純粋に組み立て、テストで各項目の判定と飛び先を確かめられるようにする。
 */
object TroubleshootChecks {

    fun collect(context: Context, vibrate: Boolean): List<TroubleshootItem> {
        val pkg = context.packageName
        val pkgUri = Uri.parse("package:$pkg")
        val nm = context.getSystemService(NotificationManager::class.java)
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        val appNotif = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)

        val calendarOk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

        val notifOk = nm?.areNotificationsEnabled() ?: true

        // 鳴動に使うチャンネル (バイブ設定で切替) を検査する。未作成なら作ってから見る
        AlarmService.ensureChannels(context)
        val channelId = if (vibrate) AlarmService.CHANNEL_ID else AlarmService.CHANNEL_ID_NOVIB
        val channel = nm?.getNotificationChannel(channelId)
        val channelOk = channel != null &&
            channel.importance >= NotificationManager.IMPORTANCE_HIGH &&
            channel.lockscreenVisibility != Notification.VISIBILITY_SECRET

        val batteryOk = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(pkg) ?: true

        val bgRestricted = Build.VERSION.SDK_INT >= 28 &&
            (context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted ?: false)

        val exactState = when {
            Build.VERSION.SDK_INT < 31 -> CheckState.NA
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() != false -> CheckState.OK
            else -> CheckState.NG
        }

        val fsiState = when {
            Build.VERSION.SDK_INT < 34 -> CheckState.NA
            runCatching { nm?.canUseFullScreenIntent() }.getOrNull() != false -> CheckState.OK
            else -> CheckState.NG
        }

        val overlayOk = Settings.canDrawOverlays(context)

        // おやすみモード: 鳴動音は USAGE_ALARM。OFF なら素通り、優先モードは「アラーム」許可が要る、
        // アラームも含めて全て遮断 (NONE) だと鳴らない
        val dndFilter = runCatching { nm?.currentInterruptionFilter }.getOrNull()
        val dndOk = runCatching {
            when (dndFilter) {
                null, NotificationManager.INTERRUPTION_FILTER_ALL,
                NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> true
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
                NotificationManager.INTERRUPTION_FILTER_PRIORITY ->
                    // 自動ルールで発動した優先モードはユーザーの既定ポリシーとは
                    // 別物が適用されるため、実効中の consolidated ポリシーを見る (API 30+)
                    (((if (Build.VERSION.SDK_INT >= 30) {
                        nm?.consolidatedNotificationPolicy
                    } else {
                        nm?.notificationPolicy
                    })?.priorityCategories ?: 0) and
                        NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0
                else -> false
            }
        }.getOrDefault(true)
        // 全遮断 (NONE) の時は優先モード設定では効かないため、総合のおやすみモード設定へ分岐する。
        // ACTION_ZEN_MODE_SETTINGS は API 30 で deprecated になり新しい compileSdk の stub には
        // 残っていないため、同等の action 文字列を直接使う (無い端末は openSafely がアプリ詳細へ逃がす)
        val dndFix = if (dndFilter == NotificationManager.INTERRUPTION_FILTER_NONE) {
            Intent("android.settings.ZEN_MODE_SETTINGS")
        } else {
            Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
        }

        fun s(ok: Boolean) = if (ok) CheckState.OK else CheckState.NG

        return listOf(
            TroubleshootItem(
                "calendar", s(calendarOk),
                if (calendarOk) "カレンダー権限あり" else "カレンダー権限がありません",
                "予定を見つけ、全画面アラーム通知をスケジュールするために必要です。",
                appDetails,
            ),
            TroubleshootItem(
                "notifications", s(notifOk),
                if (notifOk) "通知が有効です" else "通知が無効です",
                "アラームを表示できるように、アプリ通知を有効にしてアラートを許可してください。",
                appNotif,
            ),
            TroubleshootItem(
                "channel", if (!notifOk) CheckState.NG else s(channelOk),
                if (notifOk && channelOk) "アラーム通知が正しく設定されています" else "アラーム通知の設定が不足しています",
                "チャンネル「$channelId」は重要度が高く、ロック画面に表示される必要があります。",
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
            ),
            // 鳴動は AlarmManager.setAlarmClock() 経路で、Doze 中でも発火する (仕様: 電池最適化 ON のままでも鳴動)。
            // 最適化の対象でも NG にはしない。端末独自の省電力で遅れる場合の保険として案内だけする
            TroubleshootItem(
                "battery", if (batteryOk) CheckState.OK else CheckState.INFO,
                if (batteryOk) "バッテリー最適化の対象外" else "バッテリー最適化の対象です（鳴動には影響しません）",
                "アラームは最適化の対象でも鳴ります。端末独自の省電力で遅れる場合だけ、対象外にしてください。",
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
            ),
            TroubleshootItem(
                "background", if (Build.VERSION.SDK_INT < 28) CheckState.NA else s(!bgRestricted),
                if (!bgRestricted) "バックグラウンド制限なし" else "バックグラウンドが制限されています",
                "アラームが届くように、このアプリのシステムバックグラウンド制限をオフにしてください。",
                appDetails,
            ),
            TroubleshootItem(
                "exact", exactState,
                if (exactState != CheckState.NG) "正確なアラームを設定可能" else "正確なアラームが許可されていません",
                "アラームを時間どおりに鳴らすには、正確なアラームの権限が必要です。",
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri),
            ),
            TroubleshootItem(
                "fullscreen", fsiState,
                if (fsiState != CheckState.NG) "全画面アラーム通知を表示可能" else "全画面アラーム通知が許可されていません",
                "通常の目覚まし時計と同じように、アラームがロック画面に全画面表示されます。",
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri),
            ),
            TroubleshootItem(
                "overlay", s(overlayOk),
                if (overlayOk) "他のアプリの上に表示" else "他のアプリの上に表示できません",
                "他のアプリを使用中でもアラーム画面を表示する権限が許可されています。",
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
            ),
            TroubleshootItem(
                "dnd", s(dndOk),
                if (dndOk) "おやすみモード中にアラーム可能" else "おやすみモードでアラームが鳴りません",
                "おやすみモード中でもアラームが鳴るよう、おやすみモードの「アラーム」を許可してください。",
                dndFix,
            ),
        )
    }
}

/**
 * トラブルシューティング画面。各項目の状態をアイコンで示し、足りない項目はタップで設定画面へ飛ぶ。
 * 右上の更新ボタンと、設定画面から戻った時 (ON_RESUME) に再検査する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TroubleshootingScreen(settings: SettingsRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<TroubleshootItem>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        val vibrate = runCatching { settings.flow.first().vibrateWhileRinging }.getOrDefault(true)
        items = TroubleshootChecks.collect(context, vibrate)
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("トラブルシューティング") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { tick++ }, modifier = Modifier.testTag("troubleshoot_refresh")) {
                        Icon(Icons.Default.Refresh, contentDescription = "再チェック")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items, key = { it.key }) { item -> TroubleshootRow(item) { context.openSafely(item.fix) } }
        }
    }
}

private fun Context.openSafely(intent: Intent?) {
    if (intent == null) return
    val i = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // 端末によっては個別の設定画面が無い。アプリ情報へ逃がして必ずどこかへ飛ばす
    runCatching { startActivity(i) }.onFailure {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun TroubleshootRow(item: TroubleshootItem, onFix: () -> Unit) {
    val ng = item.state == CheckState.NG
    val info = item.state == CheckState.INFO
    val na = item.state == CheckState.NA
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("troubleshoot_${item.key}")
            .then(if (ng || info) Modifier.clickable(onClick = onFix) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (item.state) {
            CheckState.OK -> Icon(Icons.Default.CheckCircle, "OK", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            CheckState.NG -> Icon(Icons.Default.Error, "要対応", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
            CheckState.NA -> Icon(Icons.Outlined.Circle, "対象外", tint = dim, modifier = Modifier.size(24.dp))
            CheckState.INFO -> Icon(Icons.Default.Info, "参考", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = if (na) dim else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (na) "この Android バージョンでは設定不要です。" else item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = if (na) dim else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if ((ng || info) && item.fix != null) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "設定を開く")
        }
    }
}
