package com.calendaralarm.shared

import com.calendaralarm.shared.weather.WeatherApi
import com.calendaralarm.shared.weather.WeatherKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** WMO weather code → 天気の種類 (#367)。Android はこの種類から文字リソースを引く。 */
class WeatherKindTest {

    @Test
    fun `WMOコードを種類に対応づける`() {
        val expected = mapOf(
            0 to WeatherKind.CLEAR, 1 to WeatherKind.MAINLY_CLEAR, 2 to WeatherKind.PARTLY_CLOUDY, 3 to WeatherKind.OVERCAST,
            45 to WeatherKind.FOG, 48 to WeatherKind.FOG,
            51 to WeatherKind.DRIZZLE, 53 to WeatherKind.DRIZZLE, 55 to WeatherKind.DRIZZLE,
            56 to WeatherKind.FREEZING_DRIZZLE, 57 to WeatherKind.FREEZING_DRIZZLE,
            61 to WeatherKind.RAIN, 63 to WeatherKind.RAIN, 65 to WeatherKind.RAIN,
            66 to WeatherKind.FREEZING_RAIN, 67 to WeatherKind.FREEZING_RAIN,
            71 to WeatherKind.SNOW, 73 to WeatherKind.SNOW, 75 to WeatherKind.SNOW,
            77 to WeatherKind.SNOW_GRAINS,
            80 to WeatherKind.RAIN_SHOWERS, 81 to WeatherKind.RAIN_SHOWERS, 82 to WeatherKind.RAIN_SHOWERS,
            85 to WeatherKind.SNOW_SHOWERS, 86 to WeatherKind.SNOW_SHOWERS,
            95 to WeatherKind.THUNDERSTORM,
            96 to WeatherKind.THUNDERSTORM_HAIL, 99 to WeatherKind.THUNDERSTORM_HAIL,
        )
        expected.forEach { (code, kind) -> assertEquals(kind, WeatherApi.kindOf(code), "code $code") }
    }

    @Test
    fun `未知のコードは不明になる`() {
        listOf(-1, 4, 44, 100, 1000).forEach { assertEquals(WeatherKind.UNKNOWN, WeatherApi.kindOf(it), "code $it") }
    }

    @Test
    fun `種類は全部ちがうコードから届く`() {
        val reached = (0..99).map { WeatherApi.kindOf(it) }.toSet()
        assertEquals(WeatherKind.entries.toSet(), reached)
    }

    @Test
    fun `iOSが使う日本語の表示名は今までと同じ`() {
        assertEquals("快晴", WeatherApi.describe(0))
        assertEquals("晴れ", WeatherApi.describe(1))
        assertEquals("雹伴う雷雨", WeatherApi.describe(99))
        assertEquals("不明", WeatherApi.describe(4))
    }
}
