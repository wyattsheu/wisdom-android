package com.wisdom.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import kotlin.math.min

/**
 * 把卡片圖縮放成小工具「目前實際尺寸」。
 *
 * 過去用 cover＋放大 15% 再裁邊的做法，疊上 widget.xml 那個 ImageView
 * 自己的 centerCrop，等於裁了兩層：只要各家 launcher 回報的
 * minWidth/minHeight 跟畫面上實際容器尺寸有一點落差（不同機型系統加的
 * 小工具留白不一樣、部分 launcher 回報本來就不準），ImageView 那層就會
 * 完全不受控地再裁一次，而且裁多少純看每支手機的落差多大，跟怎麼調整
 * 大小沒有必然關係。
 *
 * 改成這裡只做等比縮放「置中塞入」（contain，不裁切），配合 widget.xml
 * 的 ImageView 也改成 fitCenter（不再二次裁切）——兩層都不裁，內容
 * 保證完整，落差只會反映成留白大小不同，不會吃到文字。
 */
object CardFramer {
    private const val BG_COLOR = "#F7F4EF"

    /** ARGB_8888 每像素 4 bytes，RemoteViews 透過 binder 傳圖有大小限制，
     *  超過就整個更新失敗。抓 1.5M 像素（約 6MB）為上限，足以覆蓋一般 widget 尺寸。 */
    private const val MAX_PIXELS = 1_500_000

    /** 依小工具目前的 minWidth/minHeight（dp）換算成實際像素 */
    fun pixelSizeFor(ctx: Context, opts: Bundle): Pair<Int, Int> {
        val density = ctx.resources.displayMetrics.density
        val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
        val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
        var w = (wDp * density).toInt().coerceIn(64, 2000)
        var h = (hDp * density).toInt().coerceIn(64, 2000)

        // 太大就等比例縮到預算內，寧可小一點也不要整個更新失敗
        val total = w.toLong() * h.toLong()
        if (total > MAX_PIXELS) {
            val k = Math.sqrt(MAX_PIXELS.toDouble() / total.toDouble())
            w = (w * k).toInt().coerceAtLeast(64)
            h = (h * k).toInt().coerceAtLeast(64)
        }
        return w to h
    }

    fun frame(path: String, targetW: Int, targetH: Int): Bitmap? {
        if (targetW <= 0 || targetH <= 0) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null

        // 只降到「夠用」的解析度，不必整張原圖解碼，省記憶體
        var sample = 1
        while (bounds.outWidth / sample > targetW * 3 && bounds.outHeight / sample > targetH * 3) sample *= 2
        val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null

        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        // contain：兩個方向都不超出目標範圍，保證卡片整張完整可見
        val scale = min(targetW / sw, targetH / sh)
        val dw = (sw * scale).toInt().coerceAtLeast(1)
        val dh = (sh * scale).toInt().coerceAtLeast(1)

        val scaled = if (dw == src.width && dh == src.height) src
                     else Bitmap.createScaledBitmap(src, dw, dh, true).also { src.recycle() }

        // 必須用 ARGB_8888：卡片底色是細膩漸層，RGB_565 只有 16 位元色，
        // 會把漸層區的色階從 5000+ 種壓到 400+ 種 → 出現色塊與色偏
        // （實測 RGB_565 的 PSNR 只有 37.9 dB，比 WebP q90 的 44 dB 還差）
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.parseColor(BG_COLOR))

        val dx = (targetW - dw) / 2f
        val dy = (targetH - dh) / 2f
        canvas.drawBitmap(scaled, dx, dy, null)
        scaled.recycle()
        return out
    }
}
