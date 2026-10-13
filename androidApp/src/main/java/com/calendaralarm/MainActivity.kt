package com.calendaralarm

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.calendaralarm.ui.AgendaScreen
import com.calendaralarm.ui.AlarmEditScreen
import com.calendaralarm.ui.AlarmsScreen
import com.calendaralarm.ui.RingSetupSheet
import com.calendaralarm.ui.SettingsScreen
import com.calendaralarm.ui.TimerScreen
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as CalendarAlarmApp

    /** メール→予定の通知から来た抽出結果 (JSON)。null でダイアログ非表示。 */
    private val extractedJson = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_EXTRACTED_EVENT)?.let {
            extractedJson.value = it
            this.intent.removeExtra(EXTRA_EXTRACTED_EVENT)
        }
    }

    override fun onResume() {
        super.onResume()
        // オンボーディングやアプリ設定で後からカレンダー権限が付いた場合に備え、
        // 画面に戻る度に Observer 登録を試す (登録済みなら即リターン)。
        app.ensureCalendarObserver()
        // 通知が届かなかった経路の抽出結果を拾う (通知権限なし端末)
        com.calendaralarm.ai.MailPrintService.consumePending(this)?.let {
            extractedJson.value = it
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_EXTRACTED_EVENT)?.let {
            extractedJson.value = it
            intent?.removeExtra(EXTRA_EXTRACTED_EVENT)
        }
        setContent {
            // recomposition 毎に Flow を作り直すと collectAsState が
            // リセットされるため remember で固定する
            val themeIdFlow = remember { app.container.settings.flow.map { it.themeId } }
            val themeId by themeIdFlow.collectAsState(initial = "default")
            CalendarAlarmTheme(themeId = themeId) {
                val bgFlow = remember { app.container.settings.flow.map { it.backgroundImageUri } }
                val bgUri by bgFlow.collectAsState(initial = null)
                AppBackground(bgUri) {
                // XML テーマは Light 固定のため、ステータスバーアイコンの明暗は
                // 実行時にこちらで切替える (ダーク時にアイコンが消える不具合の修正)。
                val darkTheme = isSystemInDarkTheme()
                androidx.compose.runtime.SideEffect {
                    androidx.core.view.WindowCompat
                        .getInsetsController(window, window.decorView)
                        .isAppearanceLightStatusBars = !darkTheme
                }

                // 通知権限が無い等でフルスクリーン通知が出せない時でも、
                // アプリを開けば鳴動画面へ辿り着けるようにする (止める手段の確保)。
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    com.calendaralarm.engine.AlarmService.ringingInstanceId.collect { id ->
                        id?.let {
                            startActivity(
                                com.calendaralarm.engine.RingingActivity.intent(
                                    this@MainActivity, it,
                                ),
                            )
                        }
                    }
                }
                // 初回起動はオンボーディング画面を挟まず予定画面へ直行する。
                // 権限誘導は予定画面上のカードと初回アラームON時のシートが担う。
                // 強制終了→手動再開で AlarmManager の予約が消えるケースに備え、
                // メイン画面に入る度に予約を再主張する (冪等)。
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    app.container.repository.resync("app start")
                }
                MainScaffold(app.container.repository, app.container.settings)
                // 更新ユーザー(onboardingDone=true)向けの「他のアプリの上に表示」一度きり案内。新規は初回アラームON時のシートが担う
                com.calendaralarm.ui.OverlayPermissionPrompt(app.container.settings, this@MainActivity)
                // メール→予定: 抽出結果が届いていれば確認ダイアログを最前面に出す
                extractedJson.value?.let { json ->
                        val ev = runCatching {
                            kotlinx.serialization.json.Json {
                                ignoreUnknownKeys = true
                            }.decodeFromString<com.calendaralarm.shared.model.ExtractedEvent>(json)
                        }.getOrNull()
                        if (ev != null) {
                            com.calendaralarm.ui.ExtractedEventDialog(
                                event = ev,
                                onDismiss = { extractedJson.value = null },
                                onSaved = {
                                    extractedJson.value = null
                                    // 新しい予定を即時取り込んでアラーム化する
                                    lifecycleScope.launch {
                                        app.container.repository.resync("event inserted")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** MailPrintService が結果を渡す extra キー (ExtractedEvent の JSON)。 */
        const val EXTRA_EXTRACTED_EVENT = "com.calendaralarm.extra.EXTRACTED_EVENT"
    }
}

/**
 * カスタム背景画像。設定で選んだ画像を全画面の最背面に敷き、
 * 読みやすさ優先でスクリム (薄暗い/薄明るいレイヤ) を被せる。
 */
@Composable
private fun AppBackground(imageUri: String?, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, imageUri) {
        value = imageUri
            ?.removePrefix("file://")
            ?.let { path ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap()
                    }.getOrNull()
                }
            }
    }
    Box(Modifier.fillMaxSize()) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // 文字を潰さないよう常時スクリムを敷く
            Box(
                Modifier.fillMaxSize().background(
                    if (dark) androidx.compose.ui.graphics.Color(0xD0101016)
                    else androidx.compose.ui.graphics.Color(0xE8F6F6FA),
                ),
            )
        }
        content()
    }
}

private data class Tab(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
)

private val tabs = listOf(
    Tab("agenda", "予定", { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "予定") }),
    Tab("alarms", "アラーム", { Icon(Icons.Default.Alarm, contentDescription = "アラーム") }),
    Tab("timer", "タイマー", { Icon(Icons.Default.Timer, contentDescription = "タイマー") }),
    Tab("settings", "設定", { Icon(Icons.Default.Settings, contentDescription = "設定") }),
)

/** 権限シートの表示要求。auto=true は初回アラームONによる自動表示 (1回きり)。 */
private data class RingSetupRequest(val subject: String?, val leadMinutes: Int?, val auto: Boolean)

/** 回転や設定アプリ往復中のプロセス再生成でもシートが消えないよう、表示要求を保存する。 */
private val RingSetupRequestSaver = androidx.compose.runtime.saveable.Saver<RingSetupRequest?, ArrayList<Any?>>(
    save = { r -> r?.let { arrayListOf<Any?>(it.subject, it.leadMinutes, it.auto) } ?: arrayListOf() },
    restore = { l ->
        if (l.isEmpty()) null else RingSetupRequest(l[0] as String?, l[1] as Int?, l[2] as Boolean)
    },
)

@Composable
private fun MainScaffold(
    repository: com.calendaralarm.data.AlarmRepository,
    settings: com.calendaralarm.data.SettingsRepository,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var ringSetupRequest by androidx.compose.runtime.saveable.rememberSaveable(
        stateSaver = RingSetupRequestSaver,
    ) {
        androidx.compose.runtime.mutableStateOf<RingSetupRequest?>(null)
    }
    // 初めてアラームをオンにした時だけ権限シートを出す (「最初の1回だけ」)。
    // 出し終えたら ringSetupDone を立てて以後は出さない。
    val onAlarmArmed: (String?, Int?) -> Unit = { subject, leadMinutes ->
        scope.launch {
            if (!settings.flow.first().ringSetupDone) {
                ringSetupRequest = RingSetupRequest(subject, leadMinutes, auto = true)
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (current != "alarm_edit") {
                // Night UI: フローティングタブバー (inset16, h66, r26, 半透明+境界)
                androidx.compose.material3.Surface(
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 26.dp)
                        .height(66.dp)
                        .fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outlineVariant,
                    ),
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEach { tab ->
                            val sel = current == tab.route
                            Column(
                                Modifier.weight(1f).fillMaxSize()
                                    .clickable {
                                        nav.navigate(tab.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                tab.icon()
                                Text(
                                    tab.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (sel) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "agenda",
            modifier = Modifier.padding(padding),
        ) {
            composable("agenda") {
                AgendaScreen(
                    repository = repository,
                    onAlarmArmed = onAlarmArmed,
                    onShowRingSetup = {
                        ringSetupRequest = RingSetupRequest(null, null, auto = false)
                    },
                )
            }
            composable("alarms") {
                AlarmsScreen(
                    repository = repository,
                    onEdit = { id -> nav.navigate("alarm_edit?id=$id") },
                    onAlarmArmed = onAlarmArmed,
                )
            }
            composable("alarm_edit?id={id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L
                AlarmEditScreen(
                    repository = repository,
                    settings = settings,
                    alarmId = id,
                    onDone = { nav.popBackStack() },
                    onAlarmArmed = onAlarmArmed,
                )
            }
            composable("timer") { TimerScreen(repository, settings) }
            composable("settings") { SettingsScreen(repository, settings) }
        }
    }

    ringSetupRequest?.let { req ->
        RingSetupSheet(
            repository = repository,
            subject = req.subject,
            leadMinutes = req.leadMinutes,
            firstTime = req.auto,
            onDismiss = {
                ringSetupRequest = null
                if (req.auto) scope.launch { settings.setRingSetupDone(true) }
            },
        )
    }
}
