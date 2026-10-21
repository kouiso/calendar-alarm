package com.calendaralarm.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
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
        // FSI経由で本画面が出せたなら、フォールバックのオーバーレイ停止UIは畳む
        // (オーバーレイは常に最前面に描画されるので共存すると二重UIになる)。
        // onResume でも畳む: 既存 Activity への onNewIntent や、生成後に
        // サービスが overlay を出し直したケースで残存するのを防ぐ。
        RingingOverlay.hide(this)

        setContent {
            val settings by app.container.settings.flow.collectAsState(initial = null)
            // 鳴動画面は端末設定に依らず常時ダーク固定 (半睡状態で眩しくしないため)
            CalendarAlarmTheme(themeId = settings?.themeId ?: "default", darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // 鳴動中に別アラームが座席を奪った時、通知は張替え (FSI が再起動
                    // されないことがある) なので onNewIntent が来ない場合がある。
                    // 鳴動中 id が別の id に変わったら追従し、停止が旧インスタンスへ飛ばないようにする
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        AlarmService.ringingInstanceId.collect { id ->
                            if (id != null && id != instanceIdState.value) instanceIdState.value = id
                        }
                    }
                    val watchedId = instanceIdState.value ?: ""
                    val rowFlow = androidx.compose.runtime.remember(watchedId) {
                        app.container.repository.instanceWithStateFlow(watchedId)
                    }
                    // id 切替直後は直前の id の行が残るので、監視中の id の行だけを使う
                    val row = rowFlow.collectAsState(initial = null).value
                        ?.takeIf { it.first.id == watchedId }
                    val instance = row?.first
                    // 通知の「停止」放送など画面外経路で終端化された時、本画面が
                    // 残り続けると無音の鳴動画面になる (早期停止の実機報告)。
                    // サービスが死んでいても DB の状態監視だけで確実に畳める。
                    val instState = row?.second
                    androidx.compose.runtime.LaunchedEffect(instState) {
                        if (instState != null &&
                            instState != com.calendaralarm.shared.model.AlarmState.PENDING &&
                            instState != com.calendaralarm.shared.model.AlarmState.FIRED
                        ) {
                            finish()
                        }
                    }
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
                    // 鳴動ごとに画面の状態 (スライダーの丸の位置を含む) を分ける。ドラッグ中に
                    // 別の鳴動へ切り替わった時、離した指で見ていない方を止めてしまわないため
                    androidx.compose.runtime.key(watchedId) {
                        // 操作は「この画面が表示している鳴動」に固定する。離した瞬間に
                        // instanceIdState が差し替わっていても別の鳴動を止めない
                        val pinned = { action: String, minutes: Int? ->
                            sendAction(
                                action, minutes,
                                // id 不明 (null) の時は従来どおり null を送り、サービス側で
                                // 「今鳴っている鳴動」に解決させる (空文字だと解決されず止まらない)
                                targetId = watchedId.ifEmpty { null },
                                finishScreen = watchedId == (instanceIdState.value ?: ""),
                            )
                        }
                        RingingScreen(
                            weather = weather,
                            title = instance?.title ?: "アラーム",
                            triggerAtMillis = instance?.triggerAtMillis ?: System.currentTimeMillis(),
                            eventStartMillis = instance?.eventStartMillis,
                            snoozeMinutes = instance?.snoozeMinutes ?: 10,
                            snoozePresets = presets,
                            canOpenSource = canOpenSource,
                            onOpenSource = { openSourcePendingIntent() },
                            onDismiss = { pinned(AlarmService.ACTION_DISMISS, null) },
                            onSnooze = { pinned(AlarmService.ACTION_SNOOZE, null) },
                            onSnoozeAt = { m -> pinned(AlarmService.ACTION_SNOOZE, m) },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 生成後にサービスがオーバーレイを出し直した場合や、覆い被さっていた
        // 状態から復帰した場合にもフォールバック停止UIが残らないように畳む
        RingingOverlay.hide(this)
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
    @androidx.annotation.VisibleForTesting
    internal fun openSourcePendingIntent() {
        // 解除待ちの間に別の鳴動が座席を奪うと instanceIdState が差し替わるので、押した時点の対象で処理する
        val id = instanceIdState.value
        // 鳴動画面はキーガード解除を要求しないので、ロック中に送ると元アプリはキーガードの裏で開き
        // 何も起きないように見える。この操作に限り解除を求め、解除できてから開いて止める
        // (止めると鳴動画面が畳まれ解除要求も取り消されるので、停止は解除後に行う)
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            km.requestDismissKeyguard(
                this,
                object : android.app.KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = sendSourceAndDismiss(id)
                },
            )
            return
        }
        sendSourceAndDismiss(id)
    }

    private fun sendSourceAndDismiss(id: String?) {
        val pi = id?.let { NotificationAlarmService.pendingOpens.remove(it) }
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
        // 対象が既に別の鳴動へ差し替わっていたら、その鳴動の画面は閉じない
        sendAction(AlarmService.ACTION_DISMISS, targetId = id, finishScreen = id == instanceIdState.value)
    }

    private fun sendAction(
        action: String,
        snoozeMinutes: Int? = null,
        targetId: String? = instanceIdState.value,
        finishScreen: Boolean = true,
    ) {
        // 押下が画面に届いた事実を先に残す。「押しても止まらない」報告を、
        // タップ未到達 (ログ無し) とサービス側の停止失敗 (TAP はあるが DISMISS 無し) に切り分ける
        val tapLabel = if (action == AlarmService.ACTION_DISMISS) "STOP_TAP" else "SNOOZE_TAP"
        // finish() で lifecycleScope が畳まれて書き込みが消えないよう、
        // アプリ寿命のスコープへ投げる (CodeRabbit/Codex #63)
        app.container.appScope.launch {
            runCatching { app.container.repository.audit(tapLabel, "画面: ${targetId ?: "(id不明)"}") }
        }
        val delivered = runCatching {
            startService(
                Intent(this, AlarmService::class.java).apply {
                    this.action = action
                    putExtra(AlarmService.EXTRA_INSTANCE_ID, targetId)
                    if (snoozeMinutes != null) {
                        putExtra(AlarmService.EXTRA_SNOOZE_MINUTES, snoozeMinutes)
                    }
                },
            )
        }.isSuccess
        if (!delivered) {
            // サービスが既に死んでいる時でも停止が必ず効くよう直接終端化する
            // (onDismissed は DB の終端化 + AlarmManager 予約の取消を含む冪等処理)
            app.container.appScope.launch {
                runCatching {
                    app.container.repository.onDismissed(targetId)
                }
            }
        }
        if (finishScreen) finish()
    }

    /**
     * ロック画面の上に出して画面を点けるだけにする。キーガード解除は要求しない:
     * PIN/パターンのロック中に要求すると、鳴動画面の上に PIN 入力 (bouncer) が重なり
     * 停止ボタンが隠れる。停止に端末のロック解除は不要。
     */
    private fun applyLockscreenFlags() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
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
        // AlarmKit 同様、小画面でも停止操作が隠れないよう上部 (日付/時刻/件名) は
        // スクロール領域、下部の操作群 (スヌーズ/スライド停止) はピン留めする構造。
        Column(
            // targetSdk 35 は edge-to-edge 強制なので、停止ボタンがナビゲーションバーの下に潜らないよう避ける
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewportHeight = maxHeight
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 内容が窓より低い時は余白を両端の weight で埋めて見た目を維持し、
                    // 高い時はスクロールに任せる (minHeight=viewport が境界条件)
                    Column(
                        modifier = Modifier.defaultMinSize(minHeight = viewportHeight),
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
            // 時計〜タイトルは残りの高さに収める。下の停止/スヌーズは固定高で先に確保され、
            // 小さい画面や大きい文字サイズでも画面外に押し出されない
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                // 本来の高さ (リング 290 + 下の文字群) に足りない分だけリングと時刻を縮める
                val ringScale = ((maxHeight - 170.dp) / 290.dp).coerceIn(0.35f, 1f)
                val ringOuter = minOf(290.dp * ringScale, maxWidth)
                val density = LocalDensity.current
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // アクセントリング 2重 (290/230) + 大時刻
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(ringOuter).border(
                                1.5.dp, accent.copy(alpha = 0.25f), CircleShape,
                            ),
                        )
                        Box(
                            Modifier.size(ringOuter * (230f / 290f)).border(
                                1.dp, accent.copy(alpha = 0.15f), CircleShape,
                            ),
                        )
                        // 時刻はリングに収める飾りなので端末の文字サイズ設定では拡大しない
                        Text(
                            text = time,
                            fontSize = with(density) { (ringOuter * (112f / 290f)).toSp() },
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.ExtraLight,
                            letterSpacing = with(density) { (ringOuter * (-4f / 290f)).toSp() },
                            color = Color(0xFFF4F5F8),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Spacer(Modifier.height(18.dp * ringScale))
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
                        overflow = TextOverflow.Ellipsis,
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
                }
            }
                    }
                }
            }
            // スヌーズは大きい1ボタンのみ: プリセット常時表示は押し間違いの元なので畳む
            Surface(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().height(80.dp),
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("スヌーズ ${snoozeMinutes}分", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFECEEF3))
                }
            }
            // 別分数スヌーズは開閉式: 「スヌーズ時間を選ぶ」タップ時のみチップを展開
            var presetsOpen by remember { mutableStateOf(false) }
            Surface(
                onClick = { presetsOpen = !presetsOpen },
                color = Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    if (presetsOpen) "スヌーズ時間を選ぶ ▴" else "スヌーズ時間を選ぶ ▾",
                    fontSize = 14.sp,
                    color = Color(0xFFB7BECB),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (presetsOpen) {
                Spacer(Modifier.height(8.dp))
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
            }
            Spacer(Modifier.height(12.dp))
            // 停止はスライド式: 誤タップで止まらないよう左端の丸を右へドラッグ
            SlideToStop(accent = accent, onAccent = onAccent, onStop = onDismiss)
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

internal const val SLIDE_THUMB_TAG = "slide-stop-thumb"

/**
 * 「スライドして停止」トラック。左端の丸を右へドラッグし、トラック幅の80%を
 * 超えた時点で停止を実行する (誤タップ防止の閾値)。TalkBack ではトラックの
 * ダブルタップ (semantics onClick) がそのまま停止になる。
 */
@Composable
private fun SlideToStop(
    accent: Color,
    onAccent: Color,
    onStop: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var trackWidthPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val thumbPx = with(density) { 64.dp.toPx() }
    val padPx = with(density) { 8.dp.toPx() }
    val travel = (trackWidthPx - thumbPx - padPx * 2).coerceAtLeast(1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(40.dp))
            .onSizeChanged { trackWidthPx = it.width.toFloat() }
            .semantics(mergeDescendants = true) {
                contentDescription = "スライドして停止"
                onClick(label = "停止", action = { onStop(); true })
            }
            .pointerInput(trackWidthPx) {
                // 掴み始めが丸の範囲内の時だけ受理: トラック右側からの横スワイプで
                // 止まってしまうと誤停止になるため起点を縛る (iOS と同じ閾値式)
                var grabbed = false
                detectDragGestures(
                    onDragStart = { start -> grabbed = start.x <= thumbPx + padPx * 3 },
                    onDragEnd = {
                        if (grabbed) {
                            scope.launch {
                                if (progress.value >= SLIDE_STOP_THRESHOLD) {
                                    progress.snapTo(1f)
                                    onStop()
                                } else {
                                    progress.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                }
                            }
                        }
                        grabbed = false
                    },
                    onDragCancel = {
                        if (grabbed) {
                            scope.launch { progress.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) }
                        }
                        grabbed = false
                    },
                ) { change, dragAmount ->
                    if (grabbed) {
                        change.consume()
                        scope.launch {
                            progress.snapTo((progress.value + dragAmount.x / travel).coerceIn(0f, 1f))
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "スライドして停止 ›",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFECEEF3).copy(alpha = (1f - progress.value).coerceIn(0f, 1f)),
            )
        }
        Box(
            modifier = Modifier
                .offset { IntOffset((padPx + progress.value * travel).toInt(), 0) }
                .testTag(SLIDE_THUMB_TAG)
                .size(64.dp)
                .clip(CircleShape)
                .background(accent),
            contentAlignment = Alignment.Center,
        ) {
            // 丸の中は停止の■ (デザイン正本: 矢印ではなく停止アイコン)
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(onAccent))
        }
    }
}
