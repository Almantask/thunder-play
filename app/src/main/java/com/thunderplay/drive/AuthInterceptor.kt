package com.thunderplay.drive

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches a fresh bearer token to every Drive request.
 *
 * Tokens last an hour, so they cannot be baked into the client at construction time. On a 401 the
 * cache is dropped and the request retried once, which covers clock skew and revoked tokens.
 */
class AuthInterceptor(private val auth: ServiceAccountAuth) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val first = chain.proceed(authorized(chain, runBlocking { auth.accessToken() }))
        if (first.code != 401) return first

        first.close()
        auth.invalidate()
        return chain.proceed(authorized(chain, runBlocking { auth.accessToken() }))
    }

    private fun authorized(chain: Interceptor.Chain, token: String) =
        chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
}
