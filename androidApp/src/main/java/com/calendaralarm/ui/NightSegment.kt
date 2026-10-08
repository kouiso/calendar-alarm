package com.calendaralarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Night UI スペックの丸型セグメントコントロール。
 * track=surfaceVariant相当 (#141821/#E6E8EE), 選択=item #262C38/#FFF, item h40, radius 16/12。
 */
@Composable
fun NightSegment(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 中立トークンはパレット非依存のため spec 直値 (track #141821/#E6E8EE, item #262C38/#FFF)
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val track = if (dark) Color(0xFF141821) else Color(0xFFE6E8EE)
    val selectedBg = if (dark) Color(0xFF262C38) else Color(0xFFFFFFFF)
    val selectedText = if (dark) Color(0xFFECEEF3) else Color(0xFF14171F)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(track)
            .padding(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (sel) selectedBg else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) selectedText else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
