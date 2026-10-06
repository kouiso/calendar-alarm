package com.calendaralarm.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.calendaralarm.CalendarAlarmApp
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 次に鳴るアラームを表示するだけの小ウィジェット。 */
class NextAlarmWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as CalendarAlarmApp
        val next = app.container.repository.pendingFlow().first().firstOrNull()
        provideContent {
            Content(
                title = next?.title,
                time = next?.triggerAtMillis?.let {
                    SimpleDateFormat("M/d(E) H:mm", Locale.JAPAN).format(Date(it))
                },
            )
        }
    }

    @Composable
    private fun Content(title: String?, time: String?) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(ColorProvider(androidx.compose.ui.graphics.Color(0xFF1B1B1D)))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "次のアラーム",
                style = TextStyle(
                    color = ColorProvider(androidx.compose.ui.graphics.Color(0xFF9EC6F5)),
                ),
            )
            if (time != null && title != null) {
                Text(
                    time,
                    style = TextStyle(
                        color = ColorProvider(androidx.compose.ui.graphics.Color.White),
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    title,
                    style = TextStyle(
                        color = ColorProvider(androidx.compose.ui.graphics.Color(0xFFBBBBBB)),
                    ),
                    maxLines = 1,
                )
            } else {
                Text(
                    "予約なし",
                    style = TextStyle(
                        color = ColorProvider(androidx.compose.ui.graphics.Color(0xFFBBBBBB)),
                    ),
                )
            }
        }
    }
}

class NextAlarmWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextAlarmWidget()
}
