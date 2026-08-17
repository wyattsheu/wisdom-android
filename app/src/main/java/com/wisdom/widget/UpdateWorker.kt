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

/**
 * 設計原則：任何一步失敗，widget 都必須顯示看得到的文字，
 * 絕不留下「靜默空白／純背景色」的狀態 —— 那種狀態沒辦法回報問題出在哪。
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val dir = File(ctx.filesDir, "widget").apply { mkdirs() }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val todayFile = File(dir, "$today.webp")

        // 一開始就先顯示「更新中」，證明 widget 的更新機制本身有在運作，
        // 而不是整個流程靜默卡死沒人知道。
        safeShowStatus(ctx, "更新中…")

        try {
            if (!todayFile.exists()) {
                val manifestFile = File(dir, "manifest.json")
                val stale = !manifestFile.exists() ||
                        System.currentTimeMillis() - manifestFile.lastModified() > 7 * 86_400_000L
                if (stale) {
                    val ok = runCatching { manifestFile.writeText(fetchText(MANIFEST_URL)) }
                    if (ok.isFailure) {
                        val err = ok.exceptionOrNull()
                        safeShowStatus(ctx, "抓取索引失敗：${err?.javaClass?.simpleName ?: "未知錯誤"}")
                    }
                }
                if (!manifestFile.exists()) {
                    return@withContext Result.retry()
                }

                val m = JSONObject(manifestFile.readText())
                val base = m.getString("base")
                val items = m.getJSONArray("items")
                if (items.length() == 0) {
                    safeShowStatus(ctx, "索引是空的")
                    return@withContext Result.failure()
                }

                val seed = today.hashCode().toLong() * 31 + ctx.packageName.hashCode()
                val pick = items.getJSONObject(Random(seed).nextInt(items.length()))
                val bytes = fetchBytes(base + pick.getString("id") + ".webp")
                todayFile.writeBytes(bytes)

                dir.listFiles()?.forEach {
                    if (it.name.endsWith(".webp") && it.name != todayFile.name) it.delete()
                }
            }

            if (!safeRender(ctx, todayFile)) {
                safeShowStatus(ctx, "渲染失敗，稍後重試")
                return@withContext Result.retry()
            }
            Result.success()
        } catch (e: Exception) {
            val fallback = dir.listFiles()?.firstOrNull { it.name.endsWith(".webp") }
            val rendered = fallback != null && safeRender(ctx, fallback)
            if (!rendered) {
                safeShowStatus(ctx, "更新失敗：${e.javaClass.simpleName} ${e.message ?: ""}".take(60))
            }
            Result.retry()
        }
    }

    /** render() 本身也可能失敗（渲染階段的例外，跟下載失敗是不同層級）
     *  —— 這裡一定要接住，不然一旦失敗，widget 會整個停在初始空白畫面。 */
    private fun safeRender(ctx: Context, file: File): Boolean =
        runCatching { render(ctx, file) }.isSuccess

    private fun safeShowStatus(ctx: Context, msg: String) {
        runCatching { showStatus(ctx, msg) }
    }

    /**
     * 桌面啟動器是另一個 App／行程，預設沒有權限讀我們的 content:// URI。
     * Manifest 的 grantUriPermissions 只是「允許授權」，必須在這裡明確授權，
     * 否則啟動器讀不到檔案 → widget 只會顯示佔位圖。
     * Android 11+ 還需要 manifest 的 <queries> 宣告，否則 queryIntentActivities
     * 會靜默回傳空結果（見 AndroidManifest.xml）。
     */
    private fun grantToHostApps(ctx: Context, uri: Uri) {
        val targets = mutableSetOf("com.android.systemui")
        runCatching {
            ctx.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            ).forEach { targets.add(it.activityInfo.packageName) }
        }
        targets.forEach { pkg ->
            runCatching { ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun render(ctx: Context, file: File) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, WisdomWidget::class.java))
        if (ids.isEmpty()) return   // 還沒有任何 widget 被放上桌面

        // 主要路徑：縮小後的 bitmap 直送。不靠 content:// URI 授權，
        // 對「不同啟動器讀不到 Uri」這類問題完全免疫，穩定性優先。
        val bmp = decodeScaled(file, 720)
        if (bmp != null) {
            for (id in ids) {
                val views = RemoteViews(ctx.packageName, R.layout.widget)
                views.setImageViewBitmap(R.id.image, bmp)
                views.setViewVisibility(R.id.status, android.view.View.GONE)
                mgr.updateAppWidget(id, views)
            }
        }

        // 加碼：如果 Uri 授權成功，改送原解析度版本覆蓋上去（錦上添花，失敗不影響上面已成功的畫面）
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "com.wisdom.widget.images", file)
            grantToHostApps(ctx, uri)
            for (id in ids) {
                val views = RemoteViews(ctx.packageName, R.layout.widget)
                views.setImageViewUri(R.id.image, uri)
                views.setViewVisibility(R.id.status, android.view.View.GONE)
                mgr.updateAppWidget(id, views)
            }
        }

        if (bmp == null) {
            throw IllegalStateException("decodeScaled 回傳 null，圖檔可能損毀：${file.length()} bytes")
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
