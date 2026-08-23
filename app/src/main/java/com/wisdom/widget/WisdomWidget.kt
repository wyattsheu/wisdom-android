package com.wisdom.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.*
import java.io.File
import java.util.concurrent.TimeUnit

class WisdomWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        kick(ctx)
    }

    /** CardFramer 現在只解圖、不裁切（裁切交給 widget.xml 的 centerCrop
     *  在真正佈局時用實際大小處理），所以這裡不再需要理會 newOptions
     *  裡的尺寸數字——保留這個 callback 純粹是因為某些 launcher 在剛
     *  放上桌面、系統設定變動時仍可能觸發一次，重繪同一張圖即可。 */
    override fun onAppWidgetOptionsChanged(
        ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(ctx, mgr, id, newOptions)
        val dir = File(ctx.filesDir, "widget")
        val file = dir.listFiles()
            ?.filter { it.name.endsWith(".webp") }
            ?.maxByOrNull { it.lastModified() } ?: return

        val bmp = CardFramer.frame(file.absolutePath) ?: return

        val views = RemoteViews(ctx.packageName, R.layout.widget)
        views.setImageViewBitmap(R.id.image, bmp)
        views.setViewVisibility(R.id.status, android.view.View.GONE)
        mgr.updateAppWidget(id, views)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == "com.wisdom.widget.REFRESH") kick(ctx)
    }

    override fun onEnabled(ctx: Context) {
        // 每 6 小時醒來一次，內部再判斷日期是否換天
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "wisdom-daily",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        )
    }

    override fun onDisabled(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork("wisdom-daily")
    }

    private fun kick(ctx: Context) {
        WorkManager.getInstance(ctx).enqueue(
            OneTimeWorkRequestBuilder<UpdateWorker>().build()
        )
    }
}
