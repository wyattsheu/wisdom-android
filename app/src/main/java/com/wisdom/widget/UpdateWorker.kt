package com.wisdom.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
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

    /** 不用 content:// Uri（先前跨 App 授權失敗的來源），bitmap 直送最穩定。
     *  裁切交給 widget.xml 的 centerCrop 在真正佈局時處理，這裡每個 widget
     *  實例都送同一張未裁切的圖即可，不用再理會各自的回報尺寸。 */
    private fun render(ctx: Context, file: File) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, WisdomWidget::class.java))
        if (ids.isEmpty()) return   // 還沒有任何 widget 被放上桌面

        val bmp = CardFramer.frame(file.absolutePath)
            ?: throw IllegalStateException("CardFramer 解圖失敗，圖檔可能損毀：${file.length()} bytes")

        for (id in ids) {
            val views = RemoteViews(ctx.packageName, R.layout.widget)
            views.setImageViewBitmap(R.id.image, bmp)
            views.setViewVisibility(R.id.status, android.view.View.GONE)
            mgr.updateAppWidget(id, views)
        }
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
