package com.calendaralarm.shared.weather

import com.calendaralarm.shared.model.CurrentWeather
import com.calendaralarm.shared.model.DailyForecast
import com.calendaralarm.shared.model.GeoPoint
import com.calendaralarm.shared.model.HourlyWeather
import com.calendaralarm.shared.model.NowForecast
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Open-Meteo による天気取得。API キー不要・非商用無料のため選択。
 * イベント場所文字列 → Geocoding → 5日予報。
 */
class WeatherApi(
    private val http: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    },
) {
    /** 場所文字列を地名検索して代表点を返す。見つからなければ null。 */
    suspend fun geocode(query: String): GeoPoint? {
        if (query.isBlank()) return null
        val res: GeocodingResponse = http.get("https://geocoding-api.open-meteo.com/v1/search") {
            parameter("name", query)
            parameter("count", 1)
            parameter("language", "ja")
            parameter("format", "json")
        }.body()
        val hit = res.results?.firstOrNull() ?: return null
        return GeoPoint(name = hit.name, latitude = hit.latitude, longitude = hit.longitude)
    }

    /** 指定地点の5日予報。 */
    suspend fun forecast(point: GeoPoint, days: Int = 5): List<DailyForecast> {
        val res: ForecastResponse = http.get("https://api.open-meteo.com/v1/forecast") {
            parameter("latitude", point.latitude)
            parameter("longitude", point.longitude)
            parameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            parameter("timezone", "auto")
            parameter("forecast_days", days)
        }.body()
        val daily = res.daily ?: return emptyList()
        return daily.time.indices.mapNotNull { i ->
            val date = runCatching { LocalDate.parse(daily.time[i]) }.getOrNull() ?: return@mapNotNull null
            DailyForecast(
                date = date,
                weatherCode = daily.weatherCode.getOrElse(i) { -1 },
                tempMax = daily.temperatureMax.getOrElse(i) { Double.NaN },
                tempMin = daily.temperatureMin.getOrElse(i) { Double.NaN },
                precipitationProbability = daily.precipitationProbability?.getOrNull(i),
            )
        }
    }

    /** イベント場所文字列から直接予報を引く。地点解決できなければ null。 */
    suspend fun forecastForLocation(location: String, days: Int = 5): List<DailyForecast>? {
        val point = geocode(location) ?: return null
        return forecast(point, days)
    }

    /**
     * 現在値 + 今後 hours 時間の時間別予報。ヘッダー予報と鳴動画面の
     * 天気演出の両方がこれ1本で足りる。失敗時は null。
     */
    suspend fun now(point: GeoPoint, hours: Int = 12): NowForecast? {
        val res = runCatching {
            http.get("https://api.open-meteo.com/v1/forecast") {
                parameter("latitude", point.latitude)
                parameter("longitude", point.longitude)
                parameter("current", "temperature_2m,weather_code,is_day")
                parameter("hourly", "temperature_2m,weather_code,precipitation_probability")
                parameter("timezone", "auto")
                parameter("forecast_hours", hours)
            }.body<NowResponse>()
        }.getOrNull() ?: return null
        val cur = res.current ?: return null
        val hourly = res.hourly?.let { h ->
            h.time.indices.mapNotNull { i ->
                val epoch = runCatching {
                    // "2026-10-04T14:00" を地点ローカル時刻として読む。
                    // ヘッダー表示では時刻文字列しか使わないので UTC 近似で保持する。
                    kotlinx.datetime.LocalDateTime.parse(h.time[i])
                        .toInstant(kotlinx.datetime.TimeZone.UTC)
                        .toEpochMilliseconds()
                }.getOrNull() ?: return@mapNotNull null
                HourlyWeather(
                    epochMillis = epoch,
                    weatherCode = h.weatherCode.getOrElse(i) { -1 },
                    temperature = h.temperature.getOrElse(i) { Double.NaN },
                    precipitationProbability = h.precipitationProbability?.getOrNull(i),
                )
            }
        } ?: emptyList()
        return NowForecast(
            current = CurrentWeather(
                weatherCode = cur.weatherCode,
                temperature = cur.temperature,
                isDay = cur.isDay != 0,
            ),
            hourly = hourly,
        )
    }

    /** 場所文字列 → 現在+時間別予報。地点解決できなければ null。 */
    suspend fun nowForLocation(location: String, hours: Int = 12): NowForecast? {
        val point = geocode(location) ?: return null
        return now(point, hours)
    }

    @Serializable
    data class GeocodingResponse(
        val results: List<GeoResult>? = null,
    )

    @Serializable
    data class GeoResult(
        val name: String,
        val latitude: Double,
        val longitude: Double,
    )

    @Serializable
    data class ForecastResponse(
        val daily: Daily? = null,
    )

    @Serializable
    data class NowResponse(
        val current: NowCurrent? = null,
        val hourly: NowHourly? = null,
    )

    @Serializable
    data class NowCurrent(
        val temperature_2m: Double = Double.NaN,
        val weather_code: Int = -1,
        val is_day: Int = 1,
    ) {
        val temperature: Double get() = temperature_2m
        val weatherCode: Int get() = weather_code
        val isDay: Int get() = is_day
    }

    @Serializable
    data class NowHourly(
        val time: List<String> = emptyList(),
        val temperature_2m: List<Double> = emptyList(),
        val weather_code: List<Int> = emptyList(),
        val precipitation_probability: List<Int?>? = null,
    ) {
        val temperature: List<Double> get() = temperature_2m
        val weatherCode: List<Int> get() = weather_code
        val precipitationProbability: List<Int?>? get() = precipitation_probability
    }

    @Serializable
    data class Daily(
        val time: List<String> = emptyList(),
        val weather_code: List<Int> = emptyList(),
        val temperature_2m_max: List<Double> = emptyList(),
        val temperature_2m_min: List<Double> = emptyList(),
        val precipitation_probability_max: List<Int?>? = null,
    ) {
        // kotlinx では snake_case プロパティを使えないため別名で公開
        val weatherCode: List<Int> get() = weather_code
        val temperatureMax: List<Double> get() = temperature_2m_max
        val temperatureMin: List<Double> get() = temperature_2m_min
        val precipitationProbability: List<Int?>? get() = precipitation_probability_max
    }

    companion object {
        /** WMO weather code → 表示名 (ja)。 */
        fun describe(code: Int): String = when (code) {
            0 -> "快晴"
            1 -> "晴れ"
            2 -> "一部曇り"
            3 -> "曇り"
            45, 48 -> "霧"
            51, 53, 55 -> "霧雨"
            56, 57 -> "着氷霧雨"
            61, 63, 65 -> "雨"
            66, 67 -> "着氷雨"
            71, 73, 75 -> "雪"
            77 -> "雪粒"
            80, 81, 82 -> "にわか雨"
            85, 86 -> "にわか雪"
            95 -> "雷雨"
            96, 99 -> "雹伴う雷雨"
            else -> "不明"
        }
    }
}
