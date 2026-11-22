package com.gabry.videodownloader

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class DlState {
    data object Idle : DlState()
    data class Running(val percent: Float, val etaSec: Long, val line: String) : DlState()
    data class Done(val uri: Uri, val name: String, val mime: String, val bytes: Long) : DlState()
    data class Failed(val message: String) : DlState()
}

/** Foreground service so downloads keep going with the screen off / app in background. */
class DownloadService : Service() {

    companion object {
        private const val NOTIF_ID = 42
        private const val PROCESS = "dl"
        val state = MutableStateFlow<DlState>(DlState.Idle)
        private var job: Job? = null

        fun start(ctx: Context, url: String, title: String, opt: QualityOption) {
            pending = Triple(url, title, opt)
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
        }

        fun cancel() {
            YoutubeDL.getInstance().destroyProcessById(PROCESS)
            job?.cancel()
            state.value = DlState.Failed("Cancelled")
        }

        @Volatile private var pending: Triple<String, String, QualityOption>? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = pending ?: run { stopSelf(); return START_NOT_STICKY }
        pending = null
        val (url, title, opt) = p
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif(title, 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        state.value = DlState.Running(0f, -1, "Starting…")
        job = App.appScope.launch {
            try {
                val result = run(url, title, opt)
                state.value = result
                finish(if (result is DlState.Done) "Saved: ${result.name}" else "Download failed")
            } catch (e: Exception) {
                if (state.value !is DlState.Failed) state.value = DlState.Failed(clean(e.message))
                finish("Download failed")
            }
        }
        return START_NOT_STICKY
    }

    private fun run(url: String, title: String, opt: QualityOption): DlState {
        val work = File(cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
        val req = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--restrict-filenames")
            addOption("-o", work.absolutePath + "/%(title).80B [%(id)s].%(ext)s")
            if (opt.audioOnly) {
                addOption("-f", "${opt.formatArg}/bestaudio/best")
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "192K")
                addOption("--embed-thumbnail")
                addOption("--add-metadata")
            } else {
                addOption("-f", "${opt.formatArg}/bestvideo*+bestaudio/best")
                addOption("--merge-output-format", "mp4")
            }
        }
        var lastNotif = 0L
        YoutubeDL.getInstance().execute(req, PROCESS) { progress, eta, line ->
            state.value = DlState.Running(progress, eta, line)
            val now = System.currentTimeMillis()
            if (now - lastNotif > 700) {
                lastNotif = now
                NotificationManagerCompat.from(this).runCatching {
                    notify(NOTIF_ID, notif(title, progress.toInt().coerceIn(0, 100), false))
                }
            }
        }
        val file = work.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: return DlState.Failed("No file was produced")
        return saveToDownloads(file)
    }

    /** Copies the file to Downloads/VideoDownloader (visible in Gallery / Files). */
    private fun saveToDownloads(file: File): DlState {
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext == "mp3") "audio/mpeg" else "video/mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VideoDownloader")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(coll, values) ?: return DlState.Failed("Could not save the file")
        contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        val size = file.length()
        file.delete()
        return DlState.Done(uri, file.name, mime, size)
    }

    private fun finish(text: String) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).runCatching {
            notify(NOTIF_ID + 1, NotificationCompat.Builder(this@DownloadService, App.CHANNEL)
                .setSmallIcon(R.drawable.ic_notif).setContentTitle(text)
                .setContentIntent(openApp()).setAutoCancel(true).build())
        }
        stopSelf()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun notif(title: String, pct: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Starting…" else "$pct%")
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()
}

/** Turns long yt-dlp errors into one readable line. */
fun clean(msg: String?): String {
    val m = msg ?: return "Unknown error"
    val line = m.lines().lastOrNull { it.contains("ERROR") } ?: m.lines().firstOrNull { it.isNotBlank() } ?: m
    val t = line.substringAfter("ERROR: ").trim()
    return when {
        t.contains("login", true) || t.contains("cookies", true) ->
            "This post needs a logged-in account (private or age-restricted). $t"
        t.contains("Unsupported URL", true) -> "This link isn't supported."
        t.contains("Unable to download webpage", true) || t.contains("resolve host", true) ->
            "No connection or the site didn't respond. Try again."
        else -> t.take(400)
    }
}
