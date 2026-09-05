package com.thunderplay.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gets the diagnostics log off the phone.
 *
 * It cannot be written to Drive by the app: a service account has no storage quota of its own, so
 * Drive accepts the empty file record and rejects the content with
 * `403 storageQuotaExceeded`. That is a hard limit of authenticating as a service account rather
 * than as a user, and no amount of retrying changes it.
 *
 * The share sheet sidesteps it entirely. Sending the log through the Drive app - or email, or a
 * chat - uploads it as *you*, against your own quota, which works.
 */
@Singleton
class DiagnosticsExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: DiagnosticsLog,
) {

    suspend fun shareIntent(): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, FILE_NAME)
        file.writeText(log.snapshot())

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Thunder Play diagnostics")
            // Some targets ignore the attachment; include the text so the log still arrives.
            putExtra(Intent.EXTRA_TEXT, log.snapshot().takeLast(MAX_INLINE))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "Send diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        const val SHARE_DIR = "shared"
        const val FILE_NAME = "thunder-play-diagnostics.txt"
        const val MAX_INLINE = 20_000
    }
}
