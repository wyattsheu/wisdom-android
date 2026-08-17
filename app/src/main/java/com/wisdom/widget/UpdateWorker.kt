package com.wisdom.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.TypedValue
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
                if (!manifestFile.exists()) return@withContext Result.retry()

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
            // 離線就沿用上一張
            dir.listFiles()?.firstOrNull { it.name.endsWith(".webp") }?.let { render(ctx, it) }
            Result.retry()
        }
    }

    private fun render(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "com.wisdom.widget.images", file)
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, WisdomWidget::class.java))

        for (id in ids) {
            val views = RemoteViews(ctx.packageName, R.layout.widget)
            // 透過 Uri 而非 Bitmap → 不受 RemoteViews 1MB 限制，原解析度直送
            views.setImageViewUri(R.id.image, uri)
            views.setViewVisibility(R.id.status, android.view.View.GONE)
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
