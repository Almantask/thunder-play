package com.thunderplay.drive

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * Thrown instead of a bare Retrofit `HttpException`, so a failure says what Google objected to.
 *
 * A Drive error carries a useful reason in its body ("Invalid Value", "File not found",
 * "insufficientFilePermissions"). Without it a 400 is indistinguishable from any other 400, which
 * is a miserable thing to debug on a phone with no cable attached.
 */
class DriveApiException(
    val status: Int,
    val reason: String,
    val detail: String,
    val request: String,
) : IOException("Drive $status on $request: $reason${if (detail.isEmpty()) "" else " - $detail"}") {

    /** A short line suitable for a snackbar. */
    val short: String get() = "Drive $status: $reason"

    companion object {

        /**
         * Builds the exception from a raw error response.
         *
         * Kept separate from the interceptor so the parsing can be tested without standing up
         * OkHttp's `Chain` plumbing.
         */
        fun from(
            status: Int,
            statusMessage: String,
            body: String,
            path: String,
            query: String?,
        ): DriveApiException = DriveApiException(
            status = status,
            reason = field(body, "message").ifEmpty {
                statusMessage.ifEmpty { "unknown error" }
            },
            detail = field(body, "reason"),
            // Query strings can be long and name folders, but never carry a token: the
            // Authorization header is not part of the URL and is never logged.
            request = describe(path, query),
        )

        /**
         * Pulls a field out of Google's error JSON without a parser.
         *
         * This runs on every failure, including ones where a parser would itself throw, so it
         * stays deliberately dumb: find the key, take the quoted string after it.
         */
        internal fun field(json: String, name: String): String {
            val marker = "\"$name\""
            val at = json.indexOf(marker)
            if (at < 0) return ""
            val colon = json.indexOf(':', at + marker.length)
            if (colon < 0) return ""
            val open = json.indexOf('"', colon)
            if (open < 0) return ""
            val close = json.indexOf('"', open + 1)
            if (close < 0) return ""
            return json.substring(open + 1, close)
        }

        internal fun describe(path: String, query: String?): String {
            if (query.isNullOrEmpty()) return path
            val trimmed = if (query.length > MAX_QUERY) query.take(MAX_QUERY) + "..." else query
            return "$path?$trimmed"
        }

        private const val MAX_QUERY = 300
    }
}

/**
 * Turns Drive's JSON error bodies into [DriveApiException].
 *
 * The body is consumed to build the message and then replaced, so downstream code can still read
 * it if it wants to.
 */
class DriveErrorInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.isSuccessful) return response

        val body = response.body
        val raw = runCatching { body?.string() }.getOrNull().orEmpty()
        response.newBuilder()
            .body(raw.toResponseBody(body?.contentType()))
            .build()
            .close()

        val url = chain.request().url
        throw DriveApiException.from(
            status = response.code,
            statusMessage = response.message,
            body = raw,
            path = url.encodedPath,
            query = url.query,
        )
    }
}
