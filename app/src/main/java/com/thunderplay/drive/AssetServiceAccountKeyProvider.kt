package com.thunderplay.drive

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import java.io.FileNotFoundException

const val SERVICE_ACCOUNT_ASSET = "drive-service-account.json"

/**
 * Reads the service-account key from assets. Absence is a normal, expected state before setup,
 * so it resolves to null rather than throwing; callers surface a setup prompt instead.
 */
class AssetServiceAccountKeyProvider(
    private val context: Context,
    moshi: Moshi,
) : ServiceAccountKeyProvider {

    private val adapter = moshi.adapter(ServiceAccountKey::class.java)

    // Cached including the "missing" outcome so a failed read isn't retried on every request.
    private val resolved: Result<ServiceAccountKey?> by lazy { runCatching { read() } }

    override fun load(): ServiceAccountKey? = resolved.getOrNull()

    private fun read(): ServiceAccountKey? = try {
        context.assets.open(SERVICE_ACCOUNT_ASSET).use { stream ->
            adapter.fromJson(stream.bufferedReader().readText())
        }
    } catch (e: FileNotFoundException) {
        Log.i(TAG, "No $SERVICE_ACCOUNT_ASSET in assets; Drive is not configured yet.")
        null
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse $SERVICE_ACCOUNT_ASSET", e)
        null
    }

    private companion object {
        const val TAG = "DriveKey"
    }
}
