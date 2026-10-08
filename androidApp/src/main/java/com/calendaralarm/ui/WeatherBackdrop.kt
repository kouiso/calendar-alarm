package com.calendaralarm.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 鳴動画面の天気演出 (元アプリのアニメ天気背景に相当)。
 * WMO weather code で雨/雪/星/雲/雷光を描き分ける。全面無地のグラデに
 * 粒子を重ねるだけなので、取得失敗・未知コードは無演出のグラデに落ちる。
 */
@Composable
fun WeatherBackdrop(weatherCode: Int, isDay: Boolean, modifier: Modifier = Modifier) {
    val base = baseGradient(weatherCode, isDay)
    val transition = rememberInfiniteTransition(label = "weather")
    // 0→1 をゆっくり繰り返す位相。粒子位置は位相+固定乱数で決まる (再生成で飛ばない)
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    // 雷は短い周期で点滅させる
    val flash by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "flash",
    )
    val drops = remember { List(60) { RainDrop(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    val flakes = remember { List(50) { RainDrop(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    val stars = remember { List(80) { RainDrop(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }

    Box(modifier.fillMaxSize().background(base)) {
        Canvas(Modifier.fillMaxSize()) {
            when (kindOf(weatherCode)) {
                Kind.RAIN -> drawRain(drops, t, streak = false)
                Kind.SHOWER, Kind.THUNDER -> {
                    drawRain(drops, t, streak = true)
                    if (kindOf(weatherCode) == Kind.THUNDER) drawFlash(flash)
                }
                Kind.SNOW -> drawSnow(flakes, t)
                Kind.CLEAR -> if (isDay) drawSun(t) else drawStars(stars, t)
                Kind.CLOUDY, Kind.FOG -> drawClouds(t, fog = kindOf(weatherCode) == Kind.FOG)
                Kind.NONE -> Unit
            }
        }
    }
}

private enum class Kind { CLEAR, CLOUDY, FOG, RAIN, SHOWER, SNOW, THUNDER, NONE }

private fun kindOf(code: Int): Kind = when (code) {
    0, 1 -> Kind.CLEAR
    2 -> Kind.CLOUDY
    3 -> Kind.CLOUDY
    45, 48 -> Kind.FOG
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67 -> Kind.RAIN
    80, 81, 82 -> Kind.SHOWER
    71, 73, 75, 77, 85, 86 -> Kind.SNOW
    95, 96, 99 -> Kind.THUNDER
    else -> Kind.NONE
}

/** 天気ごとの下地グラデ (常時ダーク前提)。 */
private fun baseGradient(code: Int, isDay: Boolean): Brush {
    val colors = when (kindOf(code)) {
        Kind.CLEAR -> if (isDay)
            listOf(Color(0xFF2B4A8C), Color(0xFF0E1633))
        else
            listOf(Color(0xFF1B1B3A), Color(0xFF06060F))
        Kind.CLOUDY -> listOf(Color(0xFF3A4160), Color(0xFF14161F))
        Kind.FOG -> listOf(Color(0xFF3F4150), Color(0xFF191A20))
        Kind.RAIN, Kind.SHOWER -> listOf(Color(0xFF24314F), Color(0xFF0A0D18))
        Kind.SNOW -> listOf(Color(0xFF39415C), Color(0xFF111320))
        Kind.THUNDER -> listOf(Color(0xFF232338), Color(0xFF08080F))
        Kind.NONE -> listOf(Color(0xFF1B1B3A), Color(0xFF0B0B12))
    }
    return Brush.verticalGradient(colors)
}

private class RainDrop(val x: Float, val speed: Float, val size: Float)

private fun DrawScope.drawRain(drops: List<RainDrop>, t: Float, streak: Boolean) {
    val color = Color(0xFF8FA8D8)
    drops.forEach { d ->
        val progress = (t * d.speed + d.size) % 1f
        val x = d.x * size.width + progress * 40f
        val y = progress * size.height
        val len = if (streak) 60f * d.size + 20f else 34f * d.size + 12f
        drawLine(
            color = color.copy(alpha = 0.35f + d.size * 0.3f),
            start = Offset(x, y),
            end = Offset(x + 8f, y + len),
            strokeWidth = if (streak) 3f else 2f,
        )
    }
}

private fun DrawScope.drawSnow(flakes: List<RainDrop>, t: Float) {
    flakes.forEach { d ->
        val progress = (t * d.speed * 0.4f + d.size) % 1f
        val sway = sin((progress * 4f + d.x * 6f) * PI.toFloat()) * 24f
        val x = d.x * size.width + sway
        val y = progress * size.height
        drawCircle(
            color = Color.White.copy(alpha = 0.25f + d.size * 0.5f),
            radius = 2.5f + d.size * 4f,
            center = Offset(x, y),
        )
    }
}

private fun DrawScope.drawStars(stars: List<RainDrop>, t: Float) {
    stars.forEach { d ->
        // 明滅: 位相をずらした sin で twinkle
        val twinkle = (sin((t * 6f + d.x * 10f) * PI.toFloat()) + 1f) / 2f
        drawCircle(
            color = Color.White.copy(alpha = 0.15f + twinkle * 0.55f),
            radius = 1f + d.size * 2.2f,
            center = Offset(d.x * size.width, d.size * size.height * 0.6f),
        )
    }
}

private fun DrawScope.drawSun(t: Float) {
    val cx = size.width * 0.78f
    val cy = size.height * 0.16f
    drawCircle(Color(0xFFFFE9A8).copy(alpha = 0.28f), radius = 130f, center = Offset(cx, cy))
    drawCircle(Color(0xFFFFE9A8).copy(alpha = 0.5f), radius = 70f, center = Offset(cx, cy))
    // ゆっくり回る光条
    for (i in 0 until 8) {
        val a = (t * 0.5f + i / 8f) * (2 * PI)
        drawLine(
            color = Color(0xFFFFE9A8).copy(alpha = 0.3f),
            start = Offset(cx + cos(a).toFloat() * 90f, cy + sin(a).toFloat() * 90f),
            end = Offset(cx + cos(a).toFloat() * 150f, cy + sin(a).toFloat() * 150f),
            strokeWidth = 6f,
        )
    }
}

private fun DrawScope.drawClouds(t: Float, fog: Boolean) {
    val color = if (fog) Color(0xFF9A9EB0) else Color(0xFF6E7590)
    for (i in 0 until 4) {
        val speed = 0.05f + i * 0.02f
        val x = (((t * speed * 10f + i * 0.33f) % 1.4f) - 0.2f) * size.width
        val y = size.height * (0.08f + i * 0.12f)
        drawCircle(color.copy(alpha = 0.12f), radius = 110f, center = Offset(x, y))
        drawCircle(color.copy(alpha = 0.12f), radius = 80f, center = Offset(x + 90f, y + 14f))
        drawCircle(color.copy(alpha = 0.12f), radius = 70f, center = Offset(x - 80f, y + 18f))
    }
}

private fun DrawScope.drawFlash(t: Float) {
    // 周期の最初の8%だけフラッシュを出す
    if (t < 0.08f) {
        drawRect(Color.White.copy(alpha = (1f - t / 0.08f) * 0.25f))
    }
}
