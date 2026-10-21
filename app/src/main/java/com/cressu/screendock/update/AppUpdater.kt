package com.cressu.screendock.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object Current : UpdateState()
    data class Available(val version: String, val url: String) : UpdateState()
    data class Downloading(val version: String) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class AppUpdater private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("airbridge_updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()
    private var downloadId = -1L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
            val dm = c.getSystemService(DownloadManager::class.java)
            dm.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) install()
                else _state.value = UpdateState.Error("Update download failed")
            }
        }
    }

    init {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
    }

    fun check(manual: Boolean = true) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        if (!manual && System.currentTimeMillis() - prefs.getLong("last_check", 0) < 86_400_000L) return
        _state.value = UpdateState.Checking
        scope.launch {
            try {
                val connection = URL(API).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "AirBridge-Android")
                if (connection.responseCode != 200) error("GitHub returned HTTP ${connection.responseCode}")
                val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val tag = release.getString("tag_name")
                val assets = release.getJSONArray("assets")
                var apk: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk", true)) { apk = asset.getString("browser_download_url"); break }
                }
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
                _state.value = if (versionCode(tag) > localVersion() && apk != null) UpdateState.Available(tag.removePrefix("v"), apk) else UpdateState.Current
            } catch (t: Throwable) { _state.value = UpdateState.Error(t.message ?: "Could not check for updates") }
        }
    }

    fun download(update: UpdateState.Available) {
        updateFile().delete()
        downloadId = context.getSystemService(DownloadManager::class.java).enqueue(
            DownloadManager.Request(Uri.parse(update.url)).setTitle("AirBridge ${update.version}")
                .setDescription("Downloading signed update")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK)
        )
        _state.value = UpdateState.Downloading(update.version)
    }

    fun install() {
        val file = updateFile()
        if (!file.exists()) { _state.value = UpdateState.Error("Downloaded update was not found"); return }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _state.value = UpdateState.Error("Allow installs from AirBridge, then tap Install again")
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun updateFile() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK)
    private fun localVersion() = context.packageManager.getPackageInfo(context.packageName, 0).let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }

    companion object {
        const val REPOSITORY_URL = "https://github.com/chatLooney/AirBridge-Receiver"
        private const val API = "https://api.github.com/repos/chatLooney/AirBridge-Receiver/releases/latest"
        private const val APK = "AirBridge-update.apk"
        @Volatile private var instance: AppUpdater? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: AppUpdater(context.applicationContext).also { instance = it } }
        internal fun versionCode(tag: String): Long = Regex("(?:^|[-+])vc(\\d+)(?:$|[-+])", RegexOption.IGNORE_CASE).find(tag.removePrefix("v"))?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }
}
