package com.calendaralarm.ui

import androidx.annotation.StringRes
import com.calendaralarm.R
import com.calendaralarm.shared.weather.WeatherKind

/**
 * 天気の種類 → 表示名の文字リソース (ja/en)。shared は文字リソースを持たないので、種類だけ受け取って Android で引く。
 * 画面では `stringResource(WeatherApi.kindOf(code).labelRes())` で使う。
 */
@StringRes
fun WeatherKind.labelRes(): Int = when (this) {
    WeatherKind.CLEAR -> R.string.weather_clear
    WeatherKind.MAINLY_CLEAR -> R.string.weather_mainly_clear
    WeatherKind.PARTLY_CLOUDY -> R.string.weather_partly_cloudy
    WeatherKind.OVERCAST -> R.string.weather_overcast
    WeatherKind.FOG -> R.string.weather_fog
    WeatherKind.DRIZZLE -> R.string.weather_drizzle
    WeatherKind.FREEZING_DRIZZLE -> R.string.weather_freezing_drizzle
    WeatherKind.RAIN -> R.string.weather_rain
    WeatherKind.FREEZING_RAIN -> R.string.weather_freezing_rain
    WeatherKind.SNOW -> R.string.weather_snow
    WeatherKind.SNOW_GRAINS -> R.string.weather_snow_grains
    WeatherKind.RAIN_SHOWERS -> R.string.weather_rain_showers
    WeatherKind.SNOW_SHOWERS -> R.string.weather_snow_showers
    WeatherKind.THUNDERSTORM -> R.string.weather_thunderstorm
    WeatherKind.THUNDERSTORM_HAIL -> R.string.weather_thunderstorm_hail
    WeatherKind.UNKNOWN -> R.string.weather_unknown
}
