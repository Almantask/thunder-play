package com.thunderplay.drive

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@JsonClass(generateAdapter = true)
internal data class TokenResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "expires_in") val expiresIn: Long,
)

/** Supplies the service-account key, or null when the app has not been set up yet. */
fun interface ServiceAccountKeyProvider {
    fun load(): ServiceAccountKey?
}

/**
 * Mints and caches Google access tokens from a service-account key.
 *
 * A service account is used instead of user OAuth because listing a pre-existing Drive folder
 * needs the restricted `drive` scope, which would require Google verification for a published
 * app and expires refresh tokens every 7 days in Testing mode. See docs/SETUP.md.
 */
class ServiceAccountAuth(
    private val keyProvider: ServiceAccountKeyProvider,
    private val http: OkHttpClient,
    moshi: Moshi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val tokenAdapter = moshi.adapter(TokenResponse::class.java)
    private val mutex = Mutex()

    private data class Cached(val token: String, val expiresAtMillis: Long)

    @Volatile
    private var cached: Cached? = null

    /** True when a key is present; used to show a setup prompt rather than failing mid-sync. */
    fun isConfigured(): Boolean = keyProvider.load() != null

    suspend fun accessToken(): String {
        cached?.let { if (clock() < it.expiresAtMillis) return it.token }
        return mutex.withLock {
            // Re-check: another caller may have refreshed while we waited on the lock.
            cached?.let { if (clock() < it.expiresAtMillis) return@withLock it.token }
            val fresh = fetchToken()
            cached = fresh
            fresh.token
        }
    }

    /** Drops the cached token so the next call re-mints. Used after a 401. */
    fun invalidate() {
        cached = null
    }

    private fun fetchToken(): Cached {
        val key = keyProvider.load() ?: throw MissingServiceAccountKey(
            "No Drive service-account key found. Add app/src/main/assets/drive-service-account.json " +
                "(see docs/SETUP.md).",
        )
        val nowSeconds = clock() / 1000
        val assertion = JwtSigner.buildAssertion(key, nowSeconds = nowSeconds)
        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", assertion)
            .build()
        val request = Request.Builder().url(key.tokenUri).post(body).build()

        http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Token exchange failed (HTTP ${response.code}): $payload")
            }
            val parsed = tokenAdapter.fromJson(payload)
                ?: throw IOException("Token exchange returned an unparseable body")
            // Refresh 5 minutes early so a long sync can't straddle an expiry.
            val expiresAt = clock() + (parsed.expiresIn - REFRESH_SKEW_SECONDS) * 1000
            return Cached(parsed.accessToken, expiresAt)
        }
    }

    private companion object {
        const val REFRESH_SKEW_SECONDS = 300L
    }
}
