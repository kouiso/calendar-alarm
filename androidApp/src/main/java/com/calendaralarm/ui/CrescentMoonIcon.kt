package com.calendaralarm.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 盤 p149 の「おやすみモード中も鳴らす」の三日月。盤の svg の path をそのまま使う (24x24)。 */
internal const val CRESCENT_MOON_PATH = "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"

internal val CrescentMoonIcon: ImageVector = ImageVector.Builder(
    name = "CrescentMoon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = addPathNodes(CRESCENT_MOON_PATH),
    fill = SolidColor(Color.Black),
).build()
