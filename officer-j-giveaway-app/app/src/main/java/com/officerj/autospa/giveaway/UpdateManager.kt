package com.officerj.autospa.giveaway

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context, private val settings: AppSettings) {
    data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

    fun check(): UpdateInfo? {
        val manifestUrl = settings.updateManifestUrl.trim()
        if (manifestUrl.isBlank()) return null
        val conn = URL(manifestUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 12000
        val root = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        conn.disconnect()
        val remoteCode = root.optInt("versionCode", 0)
        if (remoteCode <= BuildConfig.VERSION_CODE) return null
        return UpdateInfo(remoteCode, root.optString("versionName"), root.optString("apkUrl"), root.optString("notes"))
    }

    fun download(info: UpdateInfo): Long {
        require(info.apkUrl.startsWith("https://")) { "Update APK URL must use HTTPS" }
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("Officer J Giveaway ${info.versionName}")
            .setDescription("Downloading update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "officer-j-update-${info.versionCode}.apk")
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    fun launchInstaller(versionCode: Int) {
        val apk = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "officer-j-update-$versionCode.apk")
        if (!apk.exists()) throw IllegalStateException("Update download has not finished")
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
