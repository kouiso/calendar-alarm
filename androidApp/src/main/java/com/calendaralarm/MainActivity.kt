package com.calendaralarm

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import com.calendaralarm.ui.OnboardingScreen
import com.calendaralarm.ui.SettingsScreen
import com.calendaralarm.ui.TimerScreen
import com.calendaralarm.ui.theme.CalendarAlarmTheme
import androidx.lifecycle.lifecycleScope
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
                    // onboarded で既に設定済みの場合も flow は remember 必須
                    val onboardedFlow = remember {
                        app.container.settings.flow.map { it.onboardingDone }
                    }
                    val onboarded by onboardedFlow.collectAsState(initial = true)

                // カレンダー権限はメイン画面の条件にしない。
                // 無くてもタイマー・単発アラームは動く (resync が部分動作する設計)、
                // 不足分は権限ヘルスカードが誘導する。「権限なしで始める」の約束と一致。
                if (!onboarded) {
                    OnboardingScreen(
                        repository = app.container.repository,
                        settings = app.container.settings,
                        activity = this,
                    )
                } else {
                    // 強制終了→手動再開で AlarmManager の予約が消えるケースに備え、
                    // メイン画面に入る度に予約を再主張する (冪等)。
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        app.container.repository.resync("app start")
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
                    MainScaffold(app.container.repository, app.container.settings)
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

@Composable
private fun MainScaffold(
    repository: com.calendaralarm.data.AlarmRepository,
    settings: com.calendaralarm.data.SettingsRepository,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            if (current != "alarm_edit") {
                NavigationBar(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = tab.icon,
                            label = { Text(tab.label) },
                        )
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
            composable("agenda") { AgendaScreen(repository) }
            composable("alarms") {
                AlarmsScreen(
                    repository = repository,
                    onEdit = { id -> nav.navigate("alarm_edit?id=$id") },
                )
            }
            composable("alarm_edit?id={id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L
                AlarmEditScreen(
                    repository = repository,
                    settings = settings,
                    alarmId = id,
                    onDone = { nav.popBackStack() },
                )
            }
            composable("timer") { TimerScreen(repository, settings) }
            composable("settings") { SettingsScreen(repository, settings) }
        }
    }
}
