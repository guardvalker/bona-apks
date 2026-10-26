package com.guardvalker.bonaapp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

private const val RELEASES_URL = "https://api.github.com/repos/guardvalker/bona-apks/releases/latest"
private const val CHECK_EVERY_MS = 60 * 60_000L

/**
 * Autoactualización: consulta el último release de GitHub y, si es más nuevo, baja el APK de esta app y lo
 * instala con [PackageInstaller] sin pedir confirmación. Android solo lo respeta cuando la app ya es su propio
 * instalador, así que la primera actualización tras instalar a mano muestra el aviso del sistema y las siguientes no.
 */
class Updater(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val current = this.context.packageManager.getPackageInfo(this.context.packageName, 0).versionName ?: "0"

    /** Corre en un hilo aparte; como mucho una vez por hora. Los fallos de red se ignoran. */
    fun checkInBackground() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < CHECK_EVERY_MS) return
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try {
                val release = JSONObject(get(RELEASES_URL).toString(Charsets.UTF_8))
                prefs.edit().putLong("last_check", now).apply()
                val latest = release.getString("tag_name").removePrefix("v")
                val assets = release.getJSONArray("assets")
                val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name") == BuildConfig.APK_NAME }?.getString("browser_download_url")
                if (url == null || !isNewer(latest, current)) { busy.set(false); return@Thread }
                val apk = File(context.cacheDir, "update.apk")
                apk.writeBytes(get(url))
                install(apk)
            } catch (e: Exception) {
                busy.set(false)
            }
        }.start()
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "BonaApp")
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun install(file: File) {
        val action = "${context.packageName}.INSTALL_ACTION"
        if (receiverRegistered.compareAndSet(false, true)) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) ==
                        PackageInstaller.STATUS_PENDING_USER_ACTION
                    ) {
                        // Android pide confirmación manual (primera actualización tras instalar a mano).
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
                    }
                    busy.set(false)
                }
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, IntentFilter(action))
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(file.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("update.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val sender = PendingIntent.getBroadcast(
                    context, sessionId, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE,
                ).intentSender
                session.commit(sender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private companion object {
        val busy = AtomicBoolean(false)
        val receiverRegistered = AtomicBoolean(false)
    }
}

internal fun isNewer(latest: String, current: String): Boolean {
    val a = latest.split('.').map { it.toIntOrNull() ?: 0 }
    val b = current.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
