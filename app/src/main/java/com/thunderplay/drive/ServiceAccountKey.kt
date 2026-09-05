package com.thunderplay.drive

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * The subset of a Google service-account JSON key that we actually need.
 *
 * The key file is read from assets and is deliberately gitignored; see docs/SETUP.md.
 */
@JsonClass(generateAdapter = true)
data class ServiceAccountKey(
    @Json(name = "client_email") val clientEmail: String,
    @Json(name = "private_key") val privateKey: String,
    @Json(name = "private_key_id") val privateKeyId: String? = null,
    @Json(name = "token_uri") val tokenUri: String = "https://oauth2.googleapis.com/token",
    @Json(name = "project_id") val projectId: String? = null,
)

/** Raised when the app is running without a usable service-account key. */
class MissingServiceAccountKey(message: String) : IllegalStateException(message)
