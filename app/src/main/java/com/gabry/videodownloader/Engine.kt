package com.gabry.videodownloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

data class VideoInfo(
    val url: String,
    val title: String,
    val uploader: String,
    val durationSec: Double,
    val thumbnail: String?,
    val platform: String,
    val options: List<QualityOption>,
)

data class QualityOption(
    val label: String,     // "1080p"  /  "Audio"
    val sub: String,       // "MP4 · 60 fps"  /  "MP3"
    val bytes: Long,       // 0 = unknown
    val estimated: Boolean,
    val formatArg: String, // passed to -f
    val audioOnly: Boolean,
) {
    fun sizeText(): String = if (bytes <= 0) "size ?" else (if (estimated) "~" else "") + human(bytes)
}

fun human(b: Long): String {
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, if (mb < 10) "%.1f MB" else "%.0f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

object Engine {

    fun platformOf(url: String): String = when {
        url.contains("instagram.com") -> "Instagram"
        url.contains("youtube.com") || url.contains("youtu.be") -> "YouTube"
        url.contains("tiktok.com") -> "TikTok"
        else -> "Web"
    }

    /** Pulls the first http(s) link out of shared text like "Check this out! https://..." */
    fun extractUrl(text: String?): String? =
        text?.let { Regex("""https?://\S+""").find(it)?.value?.trimEnd('.', ',', ')', '"', '\'') }

    fun update(ctx: Context, silent: Boolean = false): String {
        return try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(ctx)
            val v = YoutubeDL.getInstance().version(ctx) ?: "?"
            App.engineInfo.value = "Engine: yt-dlp $v"
            if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "Already up to date ($v)" else "Updated to $v"
        } catch (e: Exception) {
            if (!silent) "Update failed: ${e.message}" else ""
        }
    }

    fun fetch(url: String): VideoInfo {
        val req = YoutubeDLRequest(url).apply {
            addOption("-J")               // full JSON info
            addOption("--no-playlist")
            addOption("--no-warnings")
        }
        val out = YoutubeDL.getInstance().execute(req).out
        var json = JSONObject(out)
        // Carousels / multi-item posts: take the first video entry
        if (!json.has("formats") && json.has("entries")) {
            val entries = json.getJSONArray("entries")
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                if (e.has("formats")) { json = e; break }
            }
        }
        val duration = json.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it }
        val webUrl = json.optString("webpage_url", url).ifBlank { url }
        return VideoInfo(
            url = webUrl,
            title = json.optString("title").ifBlank { json.optString("description").take(80) }.ifBlank { "Video" },
            uploader = json.optString("uploader").ifBlank { json.optString("channel") },
            durationSec = duration,
            thumbnail = json.optString("thumbnail").ifBlank { null },
            platform = platformOf(webUrl),
            options = buildOptions(json, duration),
        )
    }

    private class Fmt(
        val id: String, val ext: String, val height: Int, val fps: Int,
        val hasVideo: Boolean, val hasAudio: Boolean,
        val bytes: Long, val estimated: Boolean, val tbr: Double, val vcodec: String,
    )

    private fun sizeOf(f: JSONObject, duration: Double): Pair<Long, Boolean> {
        val exact = f.optLong("filesize", 0L)
        if (exact > 0) return exact to false
        val approx = f.optLong("filesize_approx", 0L)
        if (approx > 0) return approx to true
        val tbr = f.optDouble("tbr", 0.0)
        if (tbr > 0 && duration > 0) return (tbr * 1000 / 8 * duration).roundToLong() to true
        return 0L to true
    }

    private fun buildOptions(json: JSONObject, duration: Double): List<QualityOption> {
        val arr = json.optJSONArray("formats") ?: return listOf(
            QualityOption("Best", "Original file", 0, true, "best", false)
        )
        val fmts = mutableListOf<Fmt>()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            val proto = f.optString("protocol")
            if (proto.contains("mhtml")) continue // storyboards
            val v = f.optString("vcodec", "none")
            val a = f.optString("acodec", "none")
            val (b, est) = sizeOf(f, duration)
            fmts += Fmt(
                id = f.optString("format_id"),
                ext = f.optString("ext"),
                height = f.optInt("height", 0),
                fps = f.optDouble("fps", 0.0).let { if (it.isNaN()) 0 else it.toInt() },
                // some sites (TikTok/Instagram) leave codecs blank on muxed files
                hasVideo = v != "none" && (v.isNotBlank() || f.optInt("height", 0) > 0),
                hasAudio = a != "none" && (a.isNotBlank() || v.isBlank()),
                bytes = b, estimated = est,
                tbr = f.optDouble("tbr", 0.0).let { if (it.isNaN()) 0.0 else it },
                vcodec = v,
            )
        }

        val audioOnly = fmts.filter { it.hasAudio && !it.hasVideo }
        // prefer m4a (plays everywhere when merged into mp4)
        val bestAudio = audioOnly.sortedWith(
            compareByDescending<Fmt> { it.ext == "m4a" }.thenByDescending { it.tbr }
        ).firstOrNull()

        val options = mutableListOf<QualityOption>()
        val videos = fmts.filter { it.hasVideo && it.height > 0 }
        for ((h, group) in videos.groupBy { it.height }.toSortedMap(compareByDescending { it })) {
            // Prefer: already has audio > H.264/mp4 (plays on any phone) > bigger bitrate
            val pick = group.sortedWith(
                compareByDescending<Fmt> { it.hasAudio }
                    .thenByDescending { it.vcodec.startsWith("avc") || it.ext == "mp4" }
                    .thenByDescending { it.fps }
                    .thenByDescending { it.tbr }
            ).first()
            val fpsTxt = if (pick.fps > 30) " · ${pick.fps} fps" else ""
            if (pick.hasAudio || bestAudio == null) {
                options += QualityOption("${h}p", "MP4$fpsTxt", pick.bytes, pick.estimated, pick.id, false)
            } else {
                val total = if (pick.bytes > 0 && bestAudio.bytes > 0) pick.bytes + bestAudio.bytes else pick.bytes
                options += QualityOption(
                    "${h}p", "MP4$fpsTxt", total, pick.estimated || bestAudio.estimated,
                    "${pick.id}+${bestAudio.id}", false
                )
            }
        }
        if (options.isEmpty()) {
            val any = fmts.lastOrNull()
            options += QualityOption("Best", "Video", any?.bytes ?: 0, true, "best", false)
        }
        // Audio only → MP3
        val audioSrc = bestAudio ?: fmts.filter { it.hasAudio }.minByOrNull { it.bytes.takeIf { b -> b > 0 } ?: Long.MAX_VALUE }
        if (audioSrc != null) {
            val mp3 = if (duration > 0) (192_000 / 8 * duration).roundToLong() else audioSrc.bytes
            options += QualityOption("Audio only", "MP3 · 192 kbps", mp3, true, audioSrc.id, true)
        }
        return options
    }
}
