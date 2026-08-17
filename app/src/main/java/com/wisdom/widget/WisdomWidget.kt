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

    /** 使用者拖拉調整 widget 大小時觸發：用目前快取的圖，依新尺寸重新裁切。
     *  不用重新下載，純粹是取景問題，馬上就能反應。 */
    override fun onAppWidgetOptionsChanged(
        ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(ctx, mgr, id, newOptions)
        val dir = File(ctx.filesDir, "widget")
        val file = dir.listFiles()
            ?.filter { it.name.endsWith(".webp") }
            ?.maxByOrNull { it.lastModified() } ?: return

        val (w, h) = CardFramer.pixelSizeFor(ctx, newOptions)
        val bmp = CardFramer.frame(file.absolutePath, w, h) ?: return

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
