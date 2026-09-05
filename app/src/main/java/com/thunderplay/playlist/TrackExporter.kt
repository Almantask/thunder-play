package com.thunderplay.playlist

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSourceInputStream
import com.thunderplay.data.TrackEntity
import com.thunderplay.drive.ServiceAccountAuth
import com.thunderplay.playback.driveCacheDataSourceFactory
import com.thunderplay.playback.streamUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports a single track as a real file for the Android share sheet.
 *
 * Reads through the same cache-backed source playback uses, so a downloaded track is shared
 * straight off disk and an un-downloaded one is fetched once and cached on the way past.
 */
@OptIn(UnstableApi::class)
@Singleton
class TrackExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val auth: ServiceAccountAuth,
) {

    suspend fun export(track: TrackEntity): Uri = withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        // A readable name matters: this is what the recipient sees in their messaging app.
        val outFile = File(outDir, safeFileName(track.title) + ".m4a")

        if (!outFile.exists() || outFile.length() == 0L) {
            val source = driveCacheDataSourceFactory(context, client, auth).createDataSource()
            val spec = DataSpec.Builder()
                .setUri(track.streamUri())
                .setKey(track.driveId)
                .build()
            DataSourceInputStream(source, spec).use { input ->
                outFile.outputStream().use(input::copyTo)
            }
        }

        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    }

    fun shareIntent(uri: Uri, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share $title")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Clears previously exported files; they are only needed until the share completes. */
    fun clearExports() {
        File(context.cacheDir, SHARE_DIR).listFiles()?.forEach { it.delete() }
    }

    private fun safeFileName(raw: String): String =
        raw.map { if (it.isLetterOrDigit() || it in "-_. ") it else '_' }
            .joinToString("")
            .trim()
            .take(80)
            .ifEmpty { "track" }

    private companion object {
        const val SHARE_DIR = "shared"
    }
}
