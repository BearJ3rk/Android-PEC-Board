package com.example.myvoiceboard

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class AvailableUpdate(val tag: String, val downloadUrl: String)

class AppUpdateManager(private val activity: MainActivity) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadId = -1L
    private var pendingApkUri: Uri? = null
    private var statusCallback: ((String) -> Unit)? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        pendingApkUri = manager.getUriForDownloadedFile(downloadId)
                        statusCallback?.invoke("Download complete. Opening Android installer…")
                        installPendingUpdate()
                    } else {
                        statusCallback?.invoke("The update download failed. Please try again.")
                    }
                }
            }
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            activity,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun stop() {
        try { activity.unregisterReceiver(downloadReceiver) } catch (_: IllegalArgumentException) { }
        executor.shutdownNow()
    }

    fun currentVersion(): String {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            activity.packageManager.getPackageInfo(
                activity.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }
        return info.versionName ?: "0.0"
    }

    fun checkForUpdate(callback: (String, AvailableUpdate?) -> Unit) {
        executor.execute {
            try {
                val connection = URL(RELEASES_API).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "PEC-Board-Android")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val releases = JSONArray(body)
                var available: AvailableUpdate? = null
                for (index in 0 until releases.length()) {
                    val release = releases.getJSONObject(index)
                    if (release.optBoolean("draft")) continue
                    val assets = release.getJSONArray("assets")
                    for (assetIndex in 0 until assets.length()) {
                        val asset = assets.getJSONObject(assetIndex)
                        val name = asset.getString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            val tag = release.getString("tag_name")
                            val url = asset.getString("browser_download_url")
                            if (isNewer(tag, currentVersion()) && isTrustedDownload(url)) {
                                available = AvailableUpdate(tag, url)
                            }
                            break
                        }
                    }
                    if (available != null) break
                }
                val result = available
                mainHandler.post {
                    if (result == null) callback("PEC Board is up to date.", null)
                    else callback("A newer version (${result.tag}) is available.", result)
                }
            } catch (_: Exception) {
                mainHandler.post { callback("Could not check for updates. Check your connection and try again.", null) }
            }
        }
    }

    fun download(update: AvailableUpdate, callback: (String) -> Unit) {
        statusCallback = callback
        val safeTag = update.tag.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val fileName = "PEC-Board-$safeTag-${System.currentTimeMillis()}.apk"
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("PEC Board ${update.tag}")
            .setDescription("Downloading app update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName)
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = manager.enqueue(request)
        callback("Downloading ${update.tag}…")
    }

    fun installPendingUpdate() {
        val uri = pendingApkUri ?: return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            statusCallback?.invoke("Allow installs from PEC Board, then return to continue.")
            return
        }
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun isTrustedDownload(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "https" && uri.host == "github.com"
    }

    private fun isNewer(tag: String, current: String): Boolean {
        val candidateParts = versionParts(tag)
        val currentParts = versionParts(current)
        val length = maxOf(candidateParts.size, currentParts.size)
        for (index in 0 until length) {
            val candidate = candidateParts.getOrElse(index) { 0 }
            val installed = currentParts.getOrElse(index) { 0 }
            if (candidate != installed) return candidate > installed
        }
        return false
    }

    private fun versionParts(value: String): List<Int> =
        Regex("\\d+").findAll(value).map { it.value.toInt() }.toList()

    companion object {
        private const val RELEASES_API =
            "https://api.github.com/repos/BearJ3rk/Android-PEC-Board/releases?per_page=10"
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}
