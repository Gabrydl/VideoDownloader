package com.gabry.videodownloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class App : Application() {

    companion object {
        const val CHANNEL = "downloads"
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** null = still starting, true = ready, false = failed */
        val engineReady = MutableStateFlow<Boolean?>(null)
        val engineInfo = MutableStateFlow("Engine: starting…")
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        appScope.launch {
            try {
                YoutubeDL.getInstance().init(this@App)
                FFmpeg.getInstance().init(this@App)
                engineReady.value = true
                engineInfo.value = "Engine: yt-dlp " + (YoutubeDL.getInstance().version(this@App) ?: "?")
                // Platforms change often: quietly keep yt-dlp up to date at every start
                Engine.update(this@App, silent = true)
            } catch (e: Exception) {
                engineReady.value = false
                engineInfo.value = "Engine failed to start: ${e.message}"
            }
        }
    }
}
