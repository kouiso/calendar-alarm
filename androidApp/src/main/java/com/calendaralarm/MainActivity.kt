package com.calendaralarm

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CalendarAlarmTheme {
                val onboarded by app.container.settings.flow
                    .map { it.onboardingDone }
                    .collectAsState(initial = true)
                val hasCalendarPerm = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_CALENDAR,
                ) == PackageManager.PERMISSION_GRANTED

                if (!onboarded || !hasCalendarPerm) {
                    OnboardingScreen(
                        repository = app.container.repository,
                        settings = app.container.settings,
                        activity = this,
                    )
                } else {
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
                NavigationBar {
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
