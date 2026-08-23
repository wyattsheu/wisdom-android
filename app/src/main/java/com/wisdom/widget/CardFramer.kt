package com.wisdom.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 只負責把卡片圖解成一張「原始長寬比、記憶體安全」的 bitmap，不做任何
 * 裁切或縮放到某個猜測尺寸。
 *
 * 這是這個檔案第 N 次改版才學到的教訓：不管我們怎麼算裁切範圍
 * （cover、contain、模糊背景、鎖定固定比例……），只要是「事先假設一個
 * 目標尺寸再裁好圖」，就一定會被某支手機的 launcher 打臉——有些
 * launcher 回報的 minWidth/minHeight 不準，有些甚至直接無視
 * resizeMode="none" 和宣告的尺寸，自己決定要用什麼形狀顯示。事先裁好的
 * 圖只要跟畫面最後真正呈現的形狀對不上，就會出現裁到文字或留下黑邊。
 *
 * 真正不會出錯的做法，是把「怎麼填滿容器」這件事完全交給
 * widget.xml 裡 ImageView 自己的 android:scaleType="centerCrop"——那是
 * Android 系統在畫面真正佈局的當下、用它自己量出來的真實大小去裁切，
 * 不是我們提前用一個可能不準的數字用猜的，所以不管容器最後被那支手機
 * 決定成什麼形狀，都保證填滿、不留白。
 */
object CardFramer {
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

        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }
}
