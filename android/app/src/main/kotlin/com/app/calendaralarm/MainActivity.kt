package com.app.calendaralarm

import io.flutter.embedding.android.FlutterActivity
import androidx.multidex.MultiDex

class MainActivity: FlutterActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }
}
