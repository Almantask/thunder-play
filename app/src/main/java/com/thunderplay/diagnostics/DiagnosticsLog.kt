package com.thunderplay.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.thunderplay.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An on-device error log that can be pushed to Drive.
 *
 * The app is installed by downloading an APK, not over a cable, so there is no logcat to read when
 * something fails. Writing the log somewhere the phone and the PC both see is the only practical
 * way to find out what went wrong.
 *
 * Nothing secret is written here: bearer tokens live in headers that are never logged, and the
 * service-account key is never read into a message. [redact] is a backstop for anything that
 * slips through a third-party exception message.
 */
@Singleton
class DiagnosticsLog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val timestamps = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)

    val file: File get() = File(context.filesDir, FILE_NAME)

    suspend fun error(tag: String, message: String, cause: Throwable? = null) =
        append("ERROR", tag, message, cause)

    suspend fun warn(tag: String, message: String, cause: Throwable? = null) =
        append("WARN", tag, message, cause)

    suspend fun info(tag: String, message: String) = append("INFO", tag, message, null)

    private suspend fun append(
        level: String,
        tag: String,
        message: String,
        cause: Throwable?,
    ) = withContext(Dispatchers.IO) {
        val line = buildString {
            append(timestamps.format(Date()))
            append("  ").append(level.padEnd(5))
            append("  ").append(tag)
            append("  ").append(redact(message))
            if (cause != null) {
                append("\n           ").append(cause::class.java.simpleName)
                append(": ").append(redact(cause.message.orEmpty()))
                cause.stackTrace.take(STACK_LINES).forEach {
                    append("\n             at ").append(it)
                }
            }
        }
        Log.println(if (level == "ERROR") Log.ERROR else Log.WARN, tag, line)

        mutex.withLock {
            runCatching {
                rotateIfNeeded()
                file.appendText(line + "\n")
            }
        }
        Unit
    }

    /** Reads the log for upload, prefixed with the context needed to make sense of it. */
    suspend fun snapshot(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val header = buildString {
                appendLine("Thunder Play diagnostics")
                appendLine("generated : " + timestamps.format(Date()))
                appendLine("app       : ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
                appendLine("device    : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("firebase  : ${if (BuildConfig.FIREBASE_CONFIGURED) "configured" else "not configured"}")
                appendLine("-".repeat(72))
            }
            header + (runCatching { file.readText() }.getOrNull() ?: "(no entries)")
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { file.delete() } }
        Unit
    }

    /**
     * Keeps the log bounded by discarding the older half once it gets too big.
     *
     * Halving rather than deleting means a failure that happened a few minutes ago is still there
     * when you go looking for it.
     */
    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() < MAX_BYTES) return
        val kept = file.readLines().let { it.drop(it.size / 2) }
        file.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    /** Strips anything token-shaped in case a library embeds one in an exception message. */
    private fun redact(text: String): String =
        text.replace(BEARER, "Bearer <redacted>")
            .replace(ACCESS_TOKEN, "access_token=<redacted>")
            .replace(PRIVATE_KEY, "<redacted private key>")

    private companion object {
        const val FILE_NAME = "diagnostics.log"
        const val MAX_BYTES = 256 * 1024L
        const val STACK_LINES = 6

        val BEARER = Regex("Bearer\\s+[A-Za-z0-9._\\-]+")
        val ACCESS_TOKEN = Regex("access_token=[A-Za-z0-9._\\-]+")
        val PRIVATE_KEY = Regex("-----BEGIN[\\s\\S]*?END[^-]*-----")
    }
}
