package com.wisdom.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

// ── 換成你自己的 GitHub Pages 網址 ──────────
private const val MANIFEST_URL =
    "https://wyattsheu.github.io/wisdom-assets/manifest.json"

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val dir = File(ctx.filesDir, "widget").apply { mkdirs() }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val todayFile = File(dir, "$today.webp")

        try {
            if (!todayFile.exists()) {
                val manifestFile = File(dir, "manifest.json")
                // manifest 一週更新一次就好
                val stale = !manifestFile.exists() ||
                        System.currentTimeMillis() - manifestFile.lastModified() > 7 * 86_400_000L
                if (stale) {
                    runCatching { manifestFile.writeText(fetchText(MANIFEST_URL)) }
                }
                if (!manifestFile.exists()) {
                    showStatus(ctx, "連線失敗，稍後重試")
                    return@withContext Result.retry()
                }

                val m = JSONObject(manifestFile.readText())
                val base = m.getString("base")
                val items = m.getJSONArray("items")
                if (items.length() == 0) return@withContext Result.failure()

                // 用日期當種子 → 同一天永遠同一張，換天才換
                val seed = today.hashCode().toLong() * 31 + ctx.packageName.hashCode()
                val pick = items.getJSONObject(Random(seed).nextInt(items.length()))
                val bytes = fetchBytes(base + pick.getString("id") + ".webp")
                todayFile.writeBytes(bytes)

                // 清掉舊圖
                dir.listFiles()?.forEach {
                    if (it.name.endsWith(".webp") && it.name != todayFile.name) it.delete()
                }
            }
            render(ctx, todayFile)
            Result.success()
        } catch (e: Exception) {
            // 離線就沿用上一張；連上一張都沒有就把原因顯示出來，不要留一片空白
            val fallback = dir.listFiles()?.firstOrNull { it.name.endsWith(".webp") }
            if (fallback != null) render(ctx, fallback)
            else showStatus(ctx, "更新失敗：${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    /**
     * 桌面啟動器是另一個 App／行程，預設沒有權限讀我們的 content:// URI。
     * Manifest 的 grantUriPermissions 只是「允許授權」，必須在這裡明確授權，
     * 否則啟動器讀不到檔案 → widget 只會顯示佔位圖（空白灰底）。
     */
    private fun grantToHostApps(ctx: Context, uri: Uri) {
        val targets = mutableSetOf("com.android.systemui")
        val pm = ctx.packageManager
        // 目前所有可當桌面的 App
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).forEach { targets.add(it.activityInfo.packageName) }
        // 已放置本 widget 的宿主 App
        AppWidgetManager.getInstance(ctx)
            .installedProviders
            .forEach { targets.add(it.provider.packageName) }

        targets.forEach { pkg ->
            runCatching {
                ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun render(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "com.wisdom.widget.images", file)
        grantToHostApps(ctx, uri)

        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, WisdomWidget::class.java))

        for (id in ids) {
            val views = RemoteViews(ctx.packageName, R.layout.widget)
            // 優先走 Uri：不受 RemoteViews ~1MB binder 限制，可送原解析度
            views.setImageViewUri(R.id.image, uri)
            views.setViewVisibility(R.id.status, android.view.View.GONE)
            mgr.updateAppWidget(id, views)

            // 保險：某些啟動器仍讀不到 Uri，改用縮小後的 bitmap 直送。
            // 尺寸壓在 binder 限制內（RGB_565 + 最長邊 720）。
            runCatching {
                decodeScaled(file, 720)?.let { bmp ->
                    val fallback = RemoteViews(ctx.packageName, R.layout.widget)
                    fallback.setImageViewBitmap(R.id.image, bmp)
                    fallback.setViewVisibility(R.id.status, android.view.View.GONE)
                    mgr.updateAppWidget(id, fallback)
                }
            }
        }
    }

    /** 讀檔並等比縮到最長邊 maxEdge，用 RGB_565 減半記憶體 */
    private fun decodeScaled(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /** 失敗時在 widget 上顯示原因，而不是留一片空白讓人猜 */
    private fun showStatus(ctx: Context, msg: String) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (id in mgr.getAppWidgetIds(ComponentName(ctx, WisdomWidget::class.java))) {
            val views = RemoteViews(ctx.packageName, R.layout.widget)
            views.setTextViewText(R.id.status, msg)
            views.setViewVisibility(R.id.status, android.view.View.VISIBLE)
            mgr.updateAppWidget(id, views)
        }
    }

    private fun conn(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
        }

    private fun fetchText(url: String): String =
        conn(url).inputStream.bufferedReader().use { it.readText() }

    private fun fetchBytes(url: String): ByteArray =
        conn(url).inputStream.use { it.readBytes() }
}
