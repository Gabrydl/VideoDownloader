package com.gabry.videodownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var loadingRow: View
    private lateinit var loadingText: TextView
    private lateinit var errorText: TextView
    private lateinit var infoCard: View
    private lateinit var thumb: ImageView
    private lateinit var titleView: TextView
    private lateinit var meta: TextView
    private lateinit var qualityList: LinearLayout
    private lateinit var btnDownload: Button
    private lateinit var progressBox: View
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var doneBox: View
    private lateinit var doneText: TextView

    private var info: VideoInfo? = null
    private var selected = 0
    private var fetchJob: Job? = null

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(prefs.getInt("theme", 0))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        loadingRow = findViewById(R.id.loadingRow)
        loadingText = findViewById(R.id.loadingText)
        errorText = findViewById(R.id.errorText)
        infoCard = findViewById(R.id.infoCard)
        thumb = findViewById(R.id.thumb)
        titleView = findViewById(R.id.title)
        meta = findViewById(R.id.meta)
        qualityList = findViewById(R.id.qualityList)
        btnDownload = findViewById(R.id.btnDownload)
        progressBox = findViewById(R.id.progressBox)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        doneBox = findViewById(R.id.doneBox)
        doneText = findViewById(R.id.doneText)

        findViewById<Button>(R.id.btnPaste).setOnClickListener {
            val text = clipboardText()
            val url = Engine.extractUrl(text)
            if (url == null) toast("No link in the clipboard") else { urlInput.setText(url); fetch(url) }
        }
        findViewById<Button>(R.id.btnFetch).setOnClickListener { fetchFromInput() }
        urlInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { fetchFromInput(); true } else false
        }
        btnDownload.setOnClickListener { startDownload() }
        findViewById<View>(R.id.btnCancel).setOnClickListener { DownloadService.cancel() }
        findViewById<Button>(R.id.btnOpen).setOnClickListener { openResult(Intent.ACTION_VIEW) }
        findViewById<Button>(R.id.btnShare).setOnClickListener { openResult(Intent.ACTION_SEND) }

        val engineText = findViewById<TextView>(R.id.engineText)
        lifecycleScope.launch { App.engineInfo.collect { engineText.text = it } }
        findViewById<View>(R.id.btnUpdate).setOnClickListener {
            toast("Updating engine…")
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { Engine.update(this@MainActivity) }
                toast(r)
            }
        }

        val btnTheme = findViewById<TextView>(R.id.btnTheme)
        btnTheme.text = themeLabel(prefs.getInt("theme", 0))
        btnTheme.setOnClickListener {
            val next = (prefs.getInt("theme", 0) + 1) % 3
            prefs.edit().putInt("theme", next).apply()
            applyTheme(next) // activity recreates itself with the new colors
        }

        lifecycleScope.launch { DownloadService.state.collect { render(it) } }
        if (savedInstanceState == null) checkForUpdate()

        if (Build13.atLeast && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // restore after theme switch / rotation
        savedInstanceState?.getString("url")?.let { if (it.isNotBlank()) { urlInput.setText(it); fetch(it) } }
            ?: handleShare(intent)
    }

    // ---- app update: looks at the latest GitHub release every time the app opens
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val rel = AppUpdate.check(this@MainActivity) ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Update available")
                .setMessage("Version ${rel.version} is ready (you have ${AppUpdate.current(this@MainActivity)}). Download and install it now? Your settings stay.")
                .setPositiveButton("Update") { _, _ -> downloadUpdate(rel) }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun downloadUpdate(rel: AppUpdate.Release) {
        val dlg = AlertDialog.Builder(this).setTitle("Downloading update").setMessage("0%").setCancelable(false).show()
        lifecycleScope.launch {
            try {
                val apk = AppUpdate.download(this@MainActivity, rel.url) { p -> runOnUiThread { dlg.setMessage("$p%") } }
                dlg.dismiss()
                if (!AppUpdate.install(this@MainActivity, apk)) toast("Allow \"Install unknown apps\" for VideoDownloader, then open the app again to update")
            } catch (e: Exception) {
                dlg.dismiss()
                toast("Update failed: ${e.message}")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("url", info?.url ?: "")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Link shared from Instagram / YouTube / TikTok via the Share menu */
    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            Engine.extractUrl(i.getStringExtra(Intent.EXTRA_TEXT))?.let { urlInput.setText(it); fetch(it) }
        }
    }

    /** When the app comes to the front with an empty box and a link copied, fill it in. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || urlInput.text.isNotBlank()) return
        val url = Engine.extractUrl(clipboardText()) ?: return
        if (Engine.platformOf(url) != "Web" && url != prefs.getString("lastClip", null)) {
            prefs.edit().putString("lastClip", url).apply()
            urlInput.setText(url)
        }
    }

    private fun clipboardText(): String? {
        val cm = getSystemService(ClipboardManager::class.java)
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }

    private fun fetchFromInput() {
        val url = Engine.extractUrl(urlInput.text.toString())
        if (url == null) { toast("Paste a valid link"); return }
        fetch(url)
    }

    private fun fetch(url: String) {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlInput.windowToken, 0)
        fetchJob?.cancel()
        info = null
        errorText.visibility = View.GONE
        infoCard.visibility = View.GONE
        loadingRow.visibility = View.VISIBLE
        loadingText.text = "Reading ${Engine.platformOf(url)} link…"
        if (DownloadService.state.value !is DlState.Running) DownloadService.state.value = DlState.Idle

        fetchJob = lifecycleScope.launch {
            try {
                if (App.engineReady.value == null) {
                    loadingText.text = "Starting engine (first launch takes a moment)…"
                    App.engineReady.first { it != null }
                }
                if (App.engineReady.value == false) throw Exception(App.engineInfo.value)
                val v = withContext(Dispatchers.IO) { Engine.fetch(url) }
                info = v
                showInfo(v)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                showError(clean(e.message))
            } finally {
                loadingRow.visibility = View.GONE
            }
        }
    }

    private fun showInfo(v: VideoInfo) {
        infoCard.visibility = View.VISIBLE
        titleView.text = v.title
        val parts = mutableListOf(v.platform)
        if (v.uploader.isNotBlank()) parts += v.uploader
        if (v.durationSec > 0) parts += duration(v.durationSec)
        meta.text = parts.joinToString("  ·  ")
        thumb.setImageDrawable(null)
        v.thumbnail?.let { t ->
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { URL(t).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
                }
                if (info === v) thumb.setImageBitmap(bmp)
            }
        }
        // default: best quality ≤ 1080p
        selected = v.options.indexOfFirst { !it.audioOnly && (it.label.removeSuffix("p").toIntOrNull() ?: 0) <= 1080 }
            .takeIf { it >= 0 } ?: 0
        buildRows(v)
    }

    private fun buildRows(v: VideoInfo) {
        qualityList.removeAllViews()
        v.options.forEachIndexed { i, opt ->
            val row = layoutInflater.inflate(R.layout.item_quality, qualityList, false)
            row.findViewById<TextView>(R.id.qLabel).text = opt.label
            row.findViewById<TextView>(R.id.qSub).text = opt.sub
            row.findViewById<TextView>(R.id.qSize).text = opt.sizeText()
            row.setBackgroundResource(if (i == selected) R.drawable.bg_row_selected else R.drawable.bg_row)
            row.setOnClickListener { selected = i; buildRows(v); updateDownloadButton() }
            qualityList.addView(row)
        }
        updateDownloadButton()
    }

    private fun updateDownloadButton() {
        val opt = info?.options?.getOrNull(selected) ?: return
        btnDownload.text = if (opt.bytes > 0) "Download  ·  ${opt.sizeText()}" else "Download"
    }

    private fun startDownload() {
        val v = info ?: return
        val opt = v.options.getOrNull(selected) ?: return
        if (DownloadService.state.value is DlState.Running) { toast("A download is already running"); return }
        DownloadService.start(this, v.url, v.title, opt)
    }

    private fun render(s: DlState) {
        when (s) {
            is DlState.Idle -> { progressBox.visibility = View.GONE; doneBox.visibility = View.GONE; btnDownload.isEnabled = true }
            is DlState.Running -> {
                if (info != null) infoCard.visibility = View.VISIBLE
                progressBox.visibility = View.VISIBLE
                doneBox.visibility = View.GONE
                btnDownload.isEnabled = false
                progress.progress = (s.percent.coerceIn(0f, 100f) * 10).toInt()
                val eta = if (s.etaSec > 0) "  ·  ${duration(s.etaSec.toDouble())} left" else ""
                val phase = when {
                    s.line.contains("Merger") || s.line.contains("ffmpeg", true) -> "Merging video + audio…"
                    s.line.contains("ExtractAudio") -> "Converting to MP3…"
                    s.percent <= 0f -> "Starting…"
                    else -> String.format(Locale.US, "%.0f%%", s.percent) + eta
                }
                progressText.text = phase
            }
            is DlState.Done -> {
                progressBox.visibility = View.GONE
                doneBox.visibility = View.VISIBLE
                btnDownload.isEnabled = true
                doneText.text = "✓  Saved to Downloads/VideoDownloader  ·  ${human(s.bytes)}"
            }
            is DlState.Failed -> {
                progressBox.visibility = View.GONE
                btnDownload.isEnabled = true
                if (s.message != "Cancelled") showError(s.message) else toast("Cancelled")
            }
        }
    }

    private fun openResult(action: String) {
        val d = DownloadService.state.value as? DlState.Done ?: return
        val i = if (action == Intent.ACTION_VIEW) {
            Intent(Intent.ACTION_VIEW).setDataAndType(d.uri, d.mime)
        } else {
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType(d.mime).putExtra(Intent.EXTRA_STREAM, d.uri), "Share")
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(i) }.onFailure { toast("No app can open this file") }
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE
    }

    private fun duration(sec: Double): String {
        val s = sec.toLong()
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun themeLabel(t: Int) = when (t) { 1 -> "Theme: Light"; 2 -> "Theme: Dark"; else -> "Theme: Auto" }

    private fun applyTheme(t: Int) = AppCompatDelegate.setDefaultNightMode(
        when (t) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    )

    private fun toast(m: String) { if (m.isNotBlank()) Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
}

private object Build13 { val atLeast = android.os.Build.VERSION.SDK_INT >= 33 }
