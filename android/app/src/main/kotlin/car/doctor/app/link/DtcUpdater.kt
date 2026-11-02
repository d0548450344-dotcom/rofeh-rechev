package car.doctor.app.link

import android.content.Context
import car.doctor.core.catalog.DtcSource
import car.doctor.core.catalog.installDtcOverlay
import car.doctor.core.catalog.knownDtcCount
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the fault-code texts current without a new APK.
 * On start it installs the last good download from disk; [refreshIfDue] fetches the
 * shared JSON from GitHub at most once a week. A download is used only if it passes
 * DtcSource.parse; the built-in table always stays underneath. Blocking: call off the
 * main thread.
 */
class DtcUpdater(context: Context) {
    private val cache = File(context.applicationContext.filesDir, "dtc-he.json")
    private val prefs = context.applicationContext.getSharedPreferences("dtc", Context.MODE_PRIVATE)

    sealed class Result {
        data class Updated(val codes: Int) : Result()
        data class UpToDate(val codes: Int) : Result()
        data class Failed(val messageHe: String) : Result()
    }

    val lastCheckedMs: Long get() = prefs.getLong("checkedAt", 0L)

    /** Installs the cached download, if there is a valid one. */
    fun loadCached() {
        val text = runCatching { cache.readText() }.getOrNull() ?: return
        DtcSource.parse(text)?.let(::installDtcOverlay)
    }

    fun refreshIfDue(nowMs: Long = System.currentTimeMillis()): Result? {
        if (nowMs - lastCheckedMs < WEEK_MS) return null
        return refresh(nowMs)
    }

    fun refresh(nowMs: Long = System.currentTimeMillis()): Result {
        val text = try {
            download()
        } catch (e: IOException) {
            return Result.Failed("לא הצלחנו להוריד עדכון. בודקים חיבור לאינטרנט ומנסים שוב.")
        }
        val parsed = DtcSource.parse(text)
            ?: return Result.Failed("קובץ העדכון לא עבר בדיקה, ולכן לא נטען. המאגר הקיים נשאר.")
        val changed = runCatching { cache.readText() }.getOrNull() != text
        if (changed) {
            val tmp = File(cache.parentFile, "dtc-he.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(cache)) {
                cache.writeText(text)
                tmp.delete()
            }
        }
        installDtcOverlay(parsed)
        prefs.edit().putLong("checkedAt", nowMs).apply()
        return if (changed) Result.Updated(knownDtcCount()) else Result.UpToDate(knownDtcCount())
    }

    private fun download(): String {
        val connection = URL(DtcSource.URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("http ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > DtcSource.MAX_BYTES) throw IOException("too large")
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
