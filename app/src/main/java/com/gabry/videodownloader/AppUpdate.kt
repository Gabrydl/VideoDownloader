package com.gabry.videodownloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks the latest GitHub release when the app opens and installs the new APK over this one. */
object AppUpdate {
    /** Set by the publish script / build: "owner/VideoDownloader". */
    const val REPO = "Gabrydl/VideoDownloader"
    const val ASSET = "VideoDownloader.apk"

    data class Release(val version: String, val url: String, val page: String)

    fun current(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }

    private fun parts(v: String): List<Int> =
        Regex("\\d+").findAll(v).map { it.value.toInt() }.take(3).toList().let { it + List(3 - it.size) { 0 } }

    fun isNewer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0..2) if (x[i] != y[i]) return x[i] > y[i]
        return false
    }

    /** The newer release, or null (up to date, offline, or anything else: no message). */
    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        if (REPO.startsWith("__")) return@withContext null
        try {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "VideoDownloader")
            if (c.responseCode != 200) return@withContext null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.optString("tag_name")
            val assets = j.optJSONArray("assets") ?: return@withContext null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").equals(ASSET, ignoreCase = true)) url = a.optString("browser_download_url")
            }
            if (url == null || !isNewer(tag, current(ctx))) null
            else Release(parts(tag).joinToString("."), url, j.optString("html_url"))
        } catch (e: Exception) { null }
    }

    suspend fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, ASSET)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "VideoDownloader")
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var got = 0L; var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n); got += n
                    if (total > 0) { val p = (got * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                }
            }
        }
        out
    }

    /** Opens Android's installer. Returns false when the "install unknown apps" switch has to be turned on first. */
    fun install(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
