package com.calendaralarm.engine

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendaralarm.CalendarAlarmApp
import com.calendaralarm.shared.model.CurrentWeather
import com.calendaralarm.ui.WeatherBackdrop
import com.calendaralarm.ui.theme.OutfitFontFamily
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 鳴動画面。ロック・消灯の上に全面表示し、停止かスヌーズのどちらかを取るまで残る。
 * 操作は AlarmService に流すだけで、状態は持たない。
 */
class RingingActivity : ComponentActivity() {

    private val app get() = application as CalendarAlarmApp
    // singleTask: 鳴動中に別アラームが発火すると onNewIntent で差し替わるため state に持つ
    private val instanceIdState = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLockscreenFlags()
        instanceIdState.value = intent.getStringExtra(EXTRA_INSTANCE_ID)

        setContent {
            val settings by app.container.settings.flow.collectAsState(initial = null)
            CalendarAlarmTheme(themeId = settings?.themeId ?: "default") {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val instance by app.container.repository
                        .instanceFlow(instanceIdState.value ?: "")
                        .map { it }
                        .collectAsState(initial = null)
                    val presets by app.container.settings.flow
                        .map { it.snoozePresets }
                        .collectAsState(initial = listOf(instance?.snoozeMinutes ?: 10))
                    // 鳴動画面の天気演出: 地点設定がある時だけ引く。
                    // 鳴動画面でネットが遅くても鳴動自体を待たせないよう非同期読み。
                    var weather by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf<CurrentWeather?>(null)
                    }
                    androidx.compose.runtime.LaunchedEffect(settings?.weatherLocation) {
                        val loc = settings?.weatherLocation.orEmpty()
                        if (settings?.weatherOnAlarmScreen == true && loc.isNotBlank()) {
                            weather = app.container.weather.nowForLocation(loc)?.current
                        }
                    }
                    // 通知アラーム由来なら「元の通知を開く」を出す
                    val instKind = instance?.kind
                    val canOpenSource = instKind ==
                        com.calendaralarm.shared.model.AlarmKind.NOTIFICATION &&
                        NotificationAlarmService.pendingOpens.containsKey(instanceIdState.value)
                    RingingScreen(
                        weather = weather,
                        title = instance?.title ?: "アラーム",
                        triggerAtMillis = instance?.triggerAtMillis ?: System.currentTimeMillis(),
                        eventStartMillis = instance?.eventStartMillis,
                        snoozeMinutes = instance?.snoozeMinutes ?: 10,
                        snoozePresets = presets,
                        canOpenSource = canOpenSource,
                        onOpenSource = { openSourcePendingIntent() },
                        onDismiss = { sendAction(AlarmService.ACTION_DISMISS) },
                        onSnooze = { sendAction(AlarmService.ACTION_SNOOZE) },
                        onSnoozeAt = { m -> sendAction(AlarmService.ACTION_SNOOZE, m) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 鳴動画面が出たまま次のアラームが発火した時、停止/スヌーズが
        // 旧インスタンスへ飛ばないよう差し替える
        instanceIdState.value = intent.getStringExtra(EXTRA_INSTANCE_ID)
    }

    /**
     * 通知アラームの「通知元を開く」。元アプリの contentIntent を送信してから鳴動を止める。
     * API34+ は BAL hardening で裸の send() がブロックされるため、
     * SystemUI と同じ MODE_BACKGROUND_ACTIVITY_START_ALLOWED をオプションで渡す。
     */
    private fun openSourcePendingIntent() {
        val pi = instanceIdState.value
            ?.let { NotificationAlarmService.pendingOpens.remove(it) }
        if (pi != null) {
            val options = if (Build.VERSION.SDK_INT >= 34) {
                android.app.ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    ).toBundle()
            } else {
                null
            }
            runCatching { pi.send(this, 0, null, null, null, null, options) }
        }
        sendAction(AlarmService.ACTION_DISMISS)
    }

    private fun sendAction(action: String, snoozeMinutes: Int? = null) {
        val delivered = runCatching {
            startService(
                Intent(this, AlarmService::class.java).apply {
                    this.action = action
                    putExtra(AlarmService.EXTRA_INSTANCE_ID, instanceIdState.value)
                    if (snoozeMinutes != null) {
                        putExtra(AlarmService.EXTRA_SNOOZE_MINUTES, snoozeMinutes)
                    }
                },
            )
        }.isSuccess
        if (!delivered) {
            // サービスが既に死んでいる時でも停止が必ず効くよう直接終端化する
            // (onDismissed は DB の終端化 + AlarmManager 予約の取消を含む冪等処理)
            lifecycleScope.launch {
                runCatching {
                    app.container.repository.onDismissed(instanceIdState.value)
                }
            }
        }
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
    eventStartMillis: Long?,
    snoozeMinutes: Int,
    snoozePresets: List<Int>,
    weather: CurrentWeather?,
    canOpenSource: Boolean = false,
    onOpenSource: () -> Unit = {},
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onSnoozeAt: (Int) -> Unit,
) {
    val time = SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(triggerAtMillis))
    val date = SimpleDateFormat("M月d日 (E)", Locale.JAPAN).format(Date(triggerAtMillis))
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    // 鳴動画面はテーマに依らず常時ダーク (Night UI spec: bg #0A0C11 フラット)
    Box(Modifier.fillMaxSize()) {
        if (weather != null) {
            WeatherBackdrop(weatherCode = weather.weatherCode, isDay = weather.isDay)
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF0A0C11)))
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 日付チップ
            Surface(
                color = Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
            ) {
                Text(
                    date,
                    fontSize = 13.sp,
                    color = Color(0xFFB7BECB),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            // アクセントリング 2重 (290/230) + 大時刻
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(290.dp).border(
                        1.5.dp, accent.copy(alpha = 0.25f), CircleShape,
                    ),
                )
                Box(
                    Modifier.size(230.dp).border(
                        1.dp, accent.copy(alpha = 0.15f), CircleShape,
                    ),
                )
                Text(
                    text = time,
                    fontSize = 112.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.ExtraLight,
                    letterSpacing = (-4).sp,
                    color = Color(0xFFF4F5F8),
                )
            }
            Spacer(Modifier.height(18.dp))
            // 開始までの残り (eventStartMillis が無い通知由来等は「まもなく開始」)
            val remain = eventStartMillis?.minus(System.currentTimeMillis())
            Text(
                if (remain != null && remain > 0) {
                    "${(remain + 30_000) / 60_000}分後に開始"
                } else {
                    "まもなく開始"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 42.sp,
                color = Color(0xFFF4F5F8),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (weather != null) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                ) {
                    Text(
                        "${com.calendaralarm.shared.weather.WeatherApi.describe(weather.weatherCode)}" +
                            " ${weather.temperature.toInt()}°",
                        fontSize = 14.sp,
                        color = Color(0xFFB7BECB),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // スヌーズプリセット 6列チップ
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                snoozePresets.take(6).forEach { m ->
                    Surface(
                        onClick = { onSnoozeAt(m) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("+${m}分", fontSize = 14.sp, color = Color(0xFFECEEF3))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // スヌーズ pill → 停止 pill (72h, 半径=高さ/2)
            Surface(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().height(72.dp),
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("スヌーズ ${snoozeMinutes}分", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFECEEF3))
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(72.dp),
                color = accent,
                shape = RoundedCornerShape(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("停止", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = onAccent)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Calendar Alarm", fontSize = 12.sp, color = Color(0xFF6E7686))
                if (canOpenSource) {
                    Text("・", fontSize = 12.sp, color = Color(0xFF6E7686))
                    TextButton(onClick = onOpenSource) {
                        Text("通知元を開く", fontSize = 12.sp, color = accent)
                    }
                }
            }
        }
    }
}
