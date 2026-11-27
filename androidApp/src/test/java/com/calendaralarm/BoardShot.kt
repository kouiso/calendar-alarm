package com.calendaralarm

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File

/**
 * デザインの板と見比べるための画像を書き出す補助。
 * 環境変数 BOARD_SHOT_DIR がある時だけ PNG を書く (CI や普段のテストでは何もしない)。
 * NATIVE グラフィックスの Robolectric で decorView を描いて保存する (captureToImage は使わない)。
 */
object BoardShot {
    /** 書き出し先。環境変数が無ければ null (何も書かない)。 */
    private fun dir(): File? =
        System.getenv("BOARD_SHOT_DIR")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }

    /** [view] を描いて `<BOARD_SHOT_DIR>/<name>.png` に書く。 */
    fun saveView(view: View, name: String) {
        val out = dir() ?: return
        if (view.width <= 0 || view.height <= 0) return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** [activity] の画面全体を `<BOARD_SHOT_DIR>/<name>.png` に書く。 */
    fun save(activity: Activity, name: String) = saveView(activity.window.decorView, name)
}
