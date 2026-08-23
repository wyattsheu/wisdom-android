package com.wisdom.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 把卡片圖解成一張「記憶體安全」的 bitmap，只做一件跟容器形狀完全無關
 * 的事：裁掉圖片本身四周內建的裝飾留白／圓角邊框（ZOOM，跟 iOS 版
 * widget.js 的常數同義）。
 *
 * 這是這個檔案第 N 次改版才學到的教訓：只要是「事先假設一個目標尺寸
 * 再裁好圖」，就一定會被某支手機的 launcher 打臉——有些 launcher 回報
 * 的 minWidth/minHeight 不準，有些甚至直接無視宣告的尺寸，自己決定要用
 * 什麼形狀顯示。所以「怎麼填滿容器」完全交給 widget.xml 的
 * android:scaleType="centerCrop"，讓 Android 系統在畫面真正佈局的當下、
 * 用它自己量出來的真實大小去處理，不管容器最後是什麼形狀都保證填滿。
 *
 * 但 ZOOM 不是在猜容器形狀，是單純把圖片自己邊緣那圈黑色留白裁掉——
 * 這個裁法固定不變，跟最後容器多大、什麼形狀完全無關，所以可以放心跟
 * 上面「不猜容器」的原則並存，不會走回頭路。
 */
object CardFramer {
    /** 等比放大裁掉圖片四周留白的倍率，跟 iOS 版 widget.js 的 ZOOM 對齊 */
    private const val ZOOM = 1.15f
    /** 垂直裁切基準：0 = 對齊頂部，0.5 = 置中，1 = 對齊底部 */
    private const val FOCUS_Y = 0.5f

    /** ARGB_8888 每像素 4 bytes，RemoteViews 透過 binder 傳圖有大小限制，
     *  超過就整個更新失敗。抓 1.5M 像素（約 6MB）為上限，足以覆蓋一般 widget 尺寸。 */
    private const val MAX_PIXELS = 1_500_000

    fun frame(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_PIXELS) {
            sample *= 2
        }

        val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null

        val cropW = (src.width / ZOOM).toInt().coerceIn(1, src.width)
        val cropH = (src.height / ZOOM).toInt().coerceIn(1, src.height)
        if (cropW == src.width && cropH == src.height) return src

        val x = ((src.width - cropW) / 2f).toInt().coerceIn(0, src.width - cropW)
        val y = ((src.height - cropH) * FOCUS_Y).toInt().coerceIn(0, src.height - cropH)
        return Bitmap.createBitmap(src, x, y, cropW, cropH).also { src.recycle() }
    }
}
