package com.calendaralarm.data.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * Google Calendar API の eventType 取得レイヤ。
 *
 * 背景: 元アプリの種別フィルタは Google Calendar の正規 eventType
 * (birthday / outOfOffice / workingLocation / task / default) に対応するが、
 * CalendarContract / EventKit どちらのOSカレンダー経路にもこの型は載らない。
 * 取得には Google アカウント連携 (calendar.events.readonly) が必須。
 * イベント照合は OS カレンダーの iCalUID ↔ API の iCalUID で行う。
 */
object GoogleCalendarTypes {

    /** 連携に要求する OAuth スコープ (イベント読取のみ)。 */
    const val CALENDAR_EVENTS_READONLY = "https://www.googleapis.com/auth/calendar.events.readonly"
    private const val AUTH_SCOPE = "oauth2:$CALENDAR_EVENTS_READONLY"
    private const val API = "https://www.googleapis.com/calendar/v3"

    /** 設定画面の「Googleカレンダー連携」ボタンが使うサインインクライアント。 */
    fun signInClient(context: Context): GoogleSignInClient = GoogleSignIn.getClient(
        context,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(CALENDAR_EVENTS_READONLY))
            .build(),
    )

    /**
     * 連携済みアカウントの Calendar API から [beginMillis, endMillis) の
     * iCalUID → eventType マップを返す。
     * 未連携/トークン失効/ネットワーク失敗は null を返す (呼び出し側は
     * googleEventType を埋めずタイトル判定へフォールバックする)。
     */
    suspend fun fetchEventTypes(
        context: Context,
        accountEmail: String,
        beginMillis: Long,
        endMillis: Long,
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        val account = AccountManager.get(context)
            .getAccountsByType("com.google")
            .firstOrNull { it.name.equals(accountEmail, ignoreCase = true) }
            // AccountManager に無い = 端末にもGoogleログイン無し。トークンは取れない。
            ?: return@withContext null
        val token = runCatching {
            GoogleAuthUtil.getToken(context, account, AUTH_SCOPE)
        }.getOrNull() ?: return@withContext null
        runCatching { fetchAllTypes(token, beginMillis, endMillis) }.getOrNull()
    }

    /** calendarList → 各カレンダーの events.list を走査して iCalUID→eventType を集める。 */
    private fun fetchAllTypes(token: String, beginMillis: Long, endMillis: Long): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (calId in calendarIds(token)) {
            var pageToken: String? = null
            do {
                val url = buildString {
                    append("$API/calendars/").append(URLEncoder.encode(calId, "UTF-8"))
                    append("/events?singleEvents=false&showDeleted=false")
                    append("&timeMin=").append(Instant.ofEpochMilli(beginMillis))
                    append("&timeMax=").append(Instant.ofEpochMilli(endMillis))
                    append("&fields=items(iCalUID,eventType),nextPageToken")
                    if (pageToken != null) append("&pageToken=").append(pageToken)
                }
                val json = getJson(url, token) ?: return out
                val items = json.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val it = items.optJSONObject(i) ?: continue
                        val uid = it.optString("iCalUID")
                        val type = it.optString("eventType")
                        if (uid.isNotEmpty() && type.isNotEmpty()) out[uid] = type
                    }
                }
                pageToken = json.optString("nextPageToken").ifEmpty { null }
            } while (pageToken != null)
        }
        return out
    }

    /** 連携アカウントが持つカレンダーID群 (primary + 共有分)。 */
    private fun calendarIds(token: String): List<String> {
        val out = mutableListOf<String>()
        var pageToken: String? = null
        do {
            var url = "$API/users/me/calendarList?fields=items(id),nextPageToken"
            if (pageToken != null) url += "&pageToken=$pageToken"
            val json = getJson(url, token) ?: return out
            val items = json.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let(out::add)
                }
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return out
    }

    private fun getJson(url: String, token: String): JSONObject? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
