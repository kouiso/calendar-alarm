package com.calendaralarm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File

/**
 * 板との見比べ用の画像を書き出す試験の部品。環境変数 BOARD_SHOT_DIR がある時だけ PNG を書く。
 * Linux の CI で値が戻った時に落ちる回帰の証跡として使う (描き方は RingingActivityDarkThemeTest と同じ decorView.draw)。
 */
internal object BoardShot {
    fun saveView(view: View, name: String) {
        val dir = System.getenv("BOARD_SHOT_DIR")?.takeIf { it.isNotBlank() } ?: return
        if (view.width <= 0 || view.height <= 0) return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val out = File(dir).apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun save(activity: android.app.Activity, name: String) = saveView(activity.window.decorView, name)
}
