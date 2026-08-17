package com.wisdom.widget

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File

/**
 * 這個 App 本體是純小工具（沒有主畫面也能運作），加這個 Activity 純粹是為了：
 *  1. 讓使用者點開圖示能看到「未裁切的完整卡片」（widget 桌面上是裁切過的）
 *  2. 有個手動觸發更新的按鈕，方便排查問題不用等 WorkManager 排程
 *  3. 部分測試工具（如 BrowserStack）要求 App 要有可啟動的畫面才能安裝互動
 */
class MainActivity : Activity() {

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var refreshBtn: Button
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        image = findViewById(R.id.fullImage)
        status = findViewById(R.id.statusText)
        refreshBtn = findViewById(R.id.refreshBtn)

        refreshBtn.setOnClickListener { triggerRefresh() }
        loadTodayImage()
    }

    override fun onResume() {
        super.onResume()
        loadTodayImage()   // 從 widget 編輯畫面切回來時，順便刷新一次
    }

    private fun widgetDir() = File(filesDir, "widget")

    private fun latestCachedImage(): File? =
        widgetDir().listFiles()?.filter { it.name.endsWith(".webp") }?.maxByOrNull { it.lastModified() }

    private fun loadTodayImage() {
        val file = latestCachedImage()
        if (file == null || file.length() == 0L) {
            status.text = getString(R.string.no_image_yet)
            status.visibility = View.VISIBLE
            image.setImageDrawable(null)
            return
        }
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        if (bmp == null) {
            status.text = getString(R.string.no_image_yet)
            status.visibility = View.VISIBLE
        } else {
            image.setImageBitmap(bmp)
            status.visibility = View.GONE
        }
    }

    private fun triggerRefresh() {
        status.text = getString(R.string.fetching)
        status.visibility = View.VISIBLE
        refreshBtn.isEnabled = false

        val req = OneTimeWorkRequestBuilder<UpdateWorker>().build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork("manual-refresh", ExistingWorkPolicy.REPLACE, req)

        // 不用 LiveData/Lifecycle（避免多加依賴）：延遲檢查幾次，抓到新結果就停。
        var attempts = 0
        val check = object : Runnable {
            override fun run() {
                attempts++
                val before = latestCachedImage()?.lastModified() ?: 0L
                loadTodayImage()
                val after = latestCachedImage()?.lastModified() ?: 0L
                if (after > before || attempts >= 8) {
                    refreshBtn.isEnabled = true
                } else {
                    ui.postDelayed(this, 1500)
                }
            }
        }
        ui.postDelayed(check, 1500)
    }
}
