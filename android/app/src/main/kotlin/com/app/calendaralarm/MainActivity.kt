package com.app.calendaralarm

import android.accounts.AccountManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.content.pm.PackageManager

/**
 * MainActivityはFlutterActivityを継承し、Flutterエンジンの設定と
 * Googleアカウントの取得を行います。
 */
class MainActivity: FlutterActivity() {
    // Flutterとネイティブコード間の通信に使用するチャネル名を定義します。
    private val CHANNEL = "com.app.calendaralarm/channel"

   /**
 * カレンダーイベントを取得してFlutter側に送信するメソッドを追加します。
 */
private fun fetchCalendarEvents(): List<Map<String, String>> {
    val eventsList = mutableListOf<Map<String, String>>()
    val projection = arrayOf(
        CalendarContract.Events._ID,
        CalendarContract.Events.TITLE,
        CalendarContract.Events.DTSTART,
        CalendarContract.Events.DTEND
    )

    val cursor = contentResolver.query(
        CalendarContract.Events.CONTENT_URI,
        projection,
        null,
        null,
        null
    )

    cursor?.use {
        while (it.moveToNext()) {
            val id = it.getString(0)
            val title = it.getString(1)
            val start = it.getLong(2)
            val end = it.getLong(3)
            eventsList.add(mapOf("id" to id, "title" to title, "start" to start.toString(), "end" to end.toString()))
        }
    }

    return eventsList
}

override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)
    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
        when (call.method) {
            "getGoogleAccounts" -> {
                val accounts = getGoogleAccounts()
                if (accounts.isNotEmpty()) {
                    result.success(accounts)
                } else {
                    result.error("UNAVAILABLE", "Googleアカウントが利用できません。", null)
                }
            }
            "fetchCalendarEvents" -> {
                val events = fetchCalendarEvents()
                if (events.isNotEmpty()) {
                    result.success(events)
                } else {
                    result.error("UNAVAILABLE", "カレンダーイベントが見つかりません。", null)
                }
            }
            else -> result.notImplemented()
        }
    }
}

    /**
     * ユーザーのGoogleアカウントのリストを取得します。
     * 必要な権限がある場合はアカウントを取得し、ない場合は権限を要求します。
     */
    private fun getGoogleAccounts(): List<String> {
        val accountsList = mutableListOf<String>()
        if (checkSelfPermission(this, android.Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED) {
            val accounts = AccountManager.get(this).getAccountsByType("com.google")
            accounts.forEach { account ->
                accountsList.add(account.name)
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.GET_ACCOUNTS), 0)
        }
        return accountsList
    }

    /**
     * ユーザーからの権限要求に対する応答を処理します。
     * 権限が付与された場合、Googleアカウントのリストを取得してFlutter側に送信します。
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val accounts = getGoogleAccounts()
            // 権限が付与された後、Flutter側にGoogleアカウントのリストを送信
            MethodChannel(flutterEngine?.dartExecutor?.binaryMessenger, CHANNEL).invokeMethod("onGoogleAccountsReceived", accounts)
        }
    }
}
