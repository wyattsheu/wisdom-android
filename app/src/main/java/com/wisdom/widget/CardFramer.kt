package com.wisdom.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import kotlin.math.max

/**
 * 把卡片圖裁切成小工具「目前實際尺寸」，邏輯跟 iOS widget.js 對齊
 * （ZOOM / FOCUS_Y 兩邊數值相同，兩個平台看起來才會一致）：
 *   ZOOM     等比填滿後再放大一點，裁掉卡片四周的留白裝飾
 *   FOCUS_Y  垂直對焦位置，0.5 = 正中，同時保住標題與內文
 *
 * 用系統 ImageView 的 centerCrop 沒辦法控制對焦點，縮到小尺寸時
 * 常常裁到不該裁的地方 —— 所以改成我們自己算好裁切範圍、直接產生
 * 目標尺寸的 bitmap，ImageView 只負責原樣顯示。
 */
object CardFramer {
    private const val ZOOM = 1.15f
    private const val FOCUS_Y = 0.5f
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
        val scale = max(targetW / sw, targetH / sh) * ZOOM
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
        val dy = if (dh >= targetH) {
            ((targetH / 2f) - (dh * FOCUS_Y)).coerceIn((targetH - dh).toFloat(), 0f)
        } else {
            (targetH - dh) / 2f
        }
        canvas.drawBitmap(scaled, dx, dy, null)
        scaled.recycle()
        return out
    }
}
