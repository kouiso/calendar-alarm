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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    private val app get() = application as CalendarAlarmApp

    override fun onResume() {
        super.onResume()
        // オンボーディングやアプリ設定で後からカレンダー権限が付いた場合に備え、
        // 画面に戻る度に Observer 登録を試す (登録済みなら即リターン)。
        app.ensureCalendarObserver()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CalendarAlarmTheme {
                // recomposition 毎に Flow を作り直すと collectAsState が
                // リセットされるため remember で固定する
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
                }
            }
        }
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
            composable("timer") { TimerScreen(repository) }
            composable("settings") { SettingsScreen(repository, settings) }
        }
    }
}
