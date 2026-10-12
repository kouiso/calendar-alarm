package com.calendaralarm.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.engine.RingingOverlay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 「他のアプリの上に表示」許可の一度きり案内。
 *
 * 初回オンボーディングにはこの許可のカードがあるが、それ以前の版から更新した
 * ユーザーは onboardingDone=true のため一度も案内されない。未許可だと、動画など
 * 他アプリ前面で鳴ったとき heads-up が畳まれた後に画面上の停止手段が無くなる。
 */
object OverlayPrompt {
    fun shouldShow(onboarded: Boolean, overlayGranted: Boolean, alreadyShown: Boolean): Boolean =
        onboarded && !overlayGranted && !alreadyShown
}

@Composable
fun OverlayPermissionPrompt(settings: SettingsRepository, activity: Activity) {
    val shownFlow = remember { settings.flow.map { it.onboardingDone to it.overlayPromptShown } }
    // 読込前 (null) は出さない: 表示済みユーザーに一瞬出るのを防ぐ
    val state by shownFlow.collectAsState(initial = null)
    val (onboarded, alreadyShown) = state ?: return
    val granted = remember { RingingOverlay.canShow(activity) }
    if (!OverlayPrompt.shouldShow(onboarded, granted, alreadyShown)) return
    val scope = rememberCoroutineScope()
    // どの閉じ方でも「表示済み」にして二度と出さない (許可の導線は設定の健全性カードに残る)
    val markShown = { scope.launch { settings.setOverlayPromptShown(true) } }
    AlertDialog(
        onDismissRequest = { markShown() },
        title = { Text("停止ボタンを確実に出す設定") },
        text = {
            Text(
                "動画などを見ている最中にアラームが鳴ると、通知がすぐ畳まれて停止ボタンが" +
                    "見えなくなることがあります。「他のアプリの上に表示」を許可すると、" +
                    "どの画面でも停止ボタンを前面に出します。",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                markShown()
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    },
                )
            }) { Text("許可する") }
        },
        dismissButton = {
            TextButton(onClick = { markShown() }) { Text("あとで") }
        },
    )
}
