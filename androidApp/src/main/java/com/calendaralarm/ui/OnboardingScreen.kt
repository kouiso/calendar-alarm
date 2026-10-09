package com.calendaralarm.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.calendaralarm.data.AlarmRepository
import com.calendaralarm.data.SettingsRepository
import kotlinx.coroutines.launch

/**
 * 初回セットアップ。アラームが「絶対鳴る」ために必要な権限を
 * 1つずつ理由つきで取る。全部取れなくても進めるが、状態は設定タブで再確認できる。
 */
@Composable
fun OnboardingScreen(
    repository: AlarmRepository,
    settings: SettingsRepository,
    activity: Activity,
) {
    val scope = rememberCoroutineScope()
    var calendarGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.READ_CALENDAR,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                activity, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        calendarGranted = grants[Manifest.permission.READ_CALENDAR] ?: calendarGranted
        notifGranted = grants[Manifest.permission.POST_NOTIFICATIONS] ?: notifGranted
    }

    // canScheduleExactAlarms() は API 31+。それ未満は正確アラームが常時許可なので true。
    val exactOk = Build.VERSION.SDK_INT < 31 ||
        (activity.getSystemService(AlarmManager::class.java)
            ?.canScheduleExactAlarms() ?: true)

    // フルスクリーン Intent は API 34+ でサイドロード版は既定拒否。
    // 既定でOFFだと鳴動画面が出ず「止められない」状態になるので初期導線に入れる。
    val fsiOk = Build.VERSION.SDK_INT < 34 ||
        (activity.getSystemService(NotificationManager::class.java)
            ?.canUseFullScreenIntent() ?: true)

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Spacer(Modifier.height(32.dp))
            // ブランドアイコン円
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(56.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        androidx.compose.foundation.shape.CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    Icons.Default.Alarm,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Calendar Alarm", style = MaterialTheme.typography.headlineMedium)
            Text(
                "カレンダーの予定をアラームで鳴らす。初回だけ権限を設定する。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(24.dp))

            OnbCard(
                title = "カレンダーの読み取り",
                desc = "予定とアラームを連動させるのに必須",
                granted = calendarGranted,
            ) {
                permLauncher.launch(
                    arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS),
                )
            }
            OnbCard(
                title = "通知",
                desc = "鳴動画面をロック画面の上に出すのに必要",
                granted = notifGranted,
            ) {
                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            OnbCard(
                title = "正確なアラーム",
                desc = "指定時刻ちょうどに鳴らすのに必要",
                granted = exactOk,
            ) {
                if (!exactOk) {
                    activity.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        },
                    )
                }
            }
            OnbCard(
                title = "フルスクリーン通知",
                desc = "鳴動時に停止画面を最前面に出すのに必要 (未許可だと通知だけになる)",
                granted = fsiOk,
            ) {
                if (!fsiOk) {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        },
                    )
                }
            }
        }

        Column {
            Button(
                onClick = {
                    scope.launch {
                        settings.setOnboardingDone(true)
                        repository.resync("onboarding")
                    }
                },
                enabled = calendarGranted && notifGranted,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("はじめる") }
            TextButton(
                onClick = {
                    scope.launch { settings.setOnboardingDone(true) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("権限なしで始める")
            }
        }
    }
}

@Composable
private fun OnbCard(title: String, desc: String, granted: Boolean, onGrant: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (granted) {
                Text("許可済み", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            } else {
                OutlinedButton(onClick = onGrant) { Text("許可") }
            }
        }
    }
}
