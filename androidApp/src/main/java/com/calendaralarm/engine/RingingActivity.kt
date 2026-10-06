package com.calendaralarm.engine

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendaralarm.CalendarAlarmApp
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 鳴動画面。ロック・消灯の上に全面表示し、停止かスヌーズのどちらかを取るまで残る。
 * 操作は AlarmService に流すだけで、状態は持たない。
 */
class RingingActivity : ComponentActivity() {

    private val app get() = application as CalendarAlarmApp
    private var instanceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLockscreenFlags()
        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)

        setContent {
            CalendarAlarmTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val instance by app.container.repository
                        .instanceFlow(instanceId ?: "")
                        .map { it }
                        .collectAsState(initial = null)
                    RingingScreen(
                        title = instance?.title ?: "アラーム",
                        triggerAtMillis = instance?.triggerAtMillis ?: System.currentTimeMillis(),
                        snoozeMinutes = instance?.snoozeMinutes ?: 10,
                        onDismiss = { sendAction(AlarmService.ACTION_DISMISS) },
                        onSnooze = { sendAction(AlarmService.ACTION_SNOOZE) },
                    )
                }
            }
        }
    }

    private fun sendAction(action: String) {
        startService(
            Intent(this, AlarmService::class.java).apply {
                this.action = action
                putExtra(AlarmService.EXTRA_INSTANCE_ID, instanceId)
            },
        )
        finish()
    }

    private fun applyLockscreenFlags() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "instance_id"

        fun intent(context: Context, instanceId: String): Intent =
            Intent(context, RingingActivity::class.java).apply {
                putExtra(EXTRA_INSTANCE_ID, instanceId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            }
    }
}

@Composable
private fun RingingScreen(
    title: String,
    triggerAtMillis: Long,
    snoozeMinutes: Int,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    val time = SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(triggerAtMillis))
    // 鳴動画面はテーマに依らず常時ダーク: 朝の暗い部屋で眩しくしない+集中させる
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color(0xFF1B1B3A), Color(0xFF0B0B12)),
            ),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(Modifier.height(32.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = time,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-4).sp,
                    color = Color.White,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFFD6D6E6),
                    textAlign = TextAlign.Center,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.size(140.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF5B57E8),
                    ),
                ) {
                    Text("停止", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = onSnooze) {
                    Text(
                        "あと${snoozeMinutes}分 (スヌーズ)",
                        fontSize = 18.sp,
                        color = Color(0xFFB9B9E0),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
