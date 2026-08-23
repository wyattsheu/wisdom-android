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
 * 把卡片圖畫成小工具「目前實際尺寸」。
 *
 * 「絕對不裁到文字」跟「絕對貼邊、零留白」這兩個目標互斥：小工具容器
 * 是什麼形狀（各家 launcher 回報的 minWidth/minHeight 準不準、系統/
 * launcher 自己又加了多少留白）沒辦法保證跟卡片圖的固定長寬比吻合，而
 * 且落差每支手機不一樣、有些手機不管怎麼調整大小都存在（不是使用者
 * 調整能解的問題）。硬要貼邊填滿（cover）就會在落差大的手機上裁到
 * 標題或內文；硬要保留完整內容（contain）就會在落差大的手機上留白，
 * 留白若用純色塊填會很突兀。
 *
 * 這裡採用「模糊放大同一張圖當底、完整卡片置中疊在上面」的做法
 * （Spotify/Apple Music 播放介面遇到固定比例圖片配任意形狀容器時的
 * 標準處理）：不管容器形狀落差多大，完整內容一定保留在上層，多出來的
 * 空間用同一張卡片的模糊延伸填滿，不會出現生硬的黑色/單色留白。
 */
object CardFramer {
    private const val BG_COLOR = "#F7F4EF"

    /** 背景模糊強度：縮到目標尺寸的 1/N 再放大回去，N 越大越模糊 */
    private const val BLUR_DOWNSCALE = 16
    /** 模糊底圖上疊一層半透明黑，避免圖片顏色太雜跟前景卡片打架 */
    private const val SCRIM_ALPHA = 60

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

        // 必須用 ARGB_8888：卡片底色是細膩漸層，RGB_565 只有 16 位元色，
        // 會把漸層區的色階從 5000+ 種壓到 400+ 種 → 出現色塊與色偏
        // （實測 RGB_565 的 PSNR 只有 37.9 dB，比 WebP q90 的 44 dB 還差）
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.parseColor(BG_COLOR))

        // 模糊底：整張圖直接壓扁縮到很小再放大——反正會糊成一片，
        // 些微變形完全看不出來，不用另外算 cover 裁切範圍
        val blurW = (targetW / BLUR_DOWNSCALE).coerceAtLeast(6)
        val blurH = (targetH / BLUR_DOWNSCALE).coerceAtLeast(6)
        val tiny = Bitmap.createScaledBitmap(src, blurW, blurH, true)
        val blurred = Bitmap.createScaledBitmap(tiny, targetW, targetH, true)
        tiny.recycle()
        canvas.drawBitmap(blurred, 0f, 0f, null)
        blurred.recycle()
        canvas.drawColor(Color.argb(SCRIM_ALPHA, 0, 0, 0))

        // 完整卡片：等比縮放置中塞入（contain），保證不管容器形狀落差
        // 多大，標題、內文、出處一定完整可見，絕不裁切
        val scale = min(targetW / sw, targetH / sh)
        val dw = (sw * scale).toInt().coerceAtLeast(1)
        val dh = (sh * scale).toInt().coerceAtLeast(1)
        val scaled = if (dw == src.width && dh == src.height) src
                     else Bitmap.createScaledBitmap(src, dw, dh, true).also { src.recycle() }

        val dx = (targetW - dw) / 2f
        val dy = (targetH - dh) / 2f
        canvas.drawBitmap(scaled, dx, dy, null)
        scaled.recycle()
        return out
    }
}
