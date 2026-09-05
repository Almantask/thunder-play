package com.thunderplay.drive

import java.util.Base64
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Builds the RS256 JWT assertion used for the service-account token exchange.
 *
 * Kept as pure functions with an injectable clock so the whole thing is unit-testable
 * without a device or a network.
 */
object JwtSigner {

    const val DRIVE_SCOPE: String = "https://www.googleapis.com/auth/drive"

    private const val PEM_BEGIN = "-----BEGIN PRIVATE KEY-----"
    private const val PEM_END = "-----END PRIVATE KEY-----"

    fun parsePrivateKey(pem: String): PrivateKey {
        val body = pem
            .replace(PEM_BEGIN, "")
            .replace(PEM_END, "")
            .filterNot { it.isWhitespace() }
        require(body.isNotEmpty()) { "Private key PEM body is empty" }
        val der = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    /**
     * @param nowSeconds current time in epoch seconds
     * @param lifetimeSeconds token lifetime; Google caps this at one hour
     */
    fun buildAssertion(
        key: ServiceAccountKey,
        scope: String = DRIVE_SCOPE,
        nowSeconds: Long,
        lifetimeSeconds: Long = 3600,
    ): String {
        val header = buildString {
            append("{")
            append(jsonPair("alg", "RS256")).append(",")
            append(jsonPair("typ", "JWT"))
            if (key.privateKeyId != null) append(",").append(jsonPair("kid", key.privateKeyId))
            append("}")
        }
        val claims = buildString {
            append("{")
            append(jsonPair("iss", key.clientEmail)).append(",")
            append(jsonPair("scope", scope)).append(",")
            append(jsonPair("aud", key.tokenUri)).append(",")
            append(numberPair("exp", nowSeconds + lifetimeSeconds)).append(",")
            append(numberPair("iat", nowSeconds))
            append("}")
        }
        val signingInput = encode(header.toByteArray()) + "." + encode(claims.toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(parsePrivateKey(key.privateKey))
            update(signingInput.toByteArray())
        }.sign()
        return signingInput + "." + encode(signature)
    }

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun jsonPair(name: String, value: String) = quote(name) + ":" + quote(value)

    private fun numberPair(name: String, value: Long) = quote(name) + ":" + value

    private fun quote(raw: String): String {
        val escaped = StringBuilder(raw.length + 2)
        escaped.append('"')
        for (c in raw) when (c) {
            '"' -> escaped.append(BACKSLASH).append('"')
            BACKSLASH -> escaped.append(BACKSLASH).append(BACKSLASH)
            '\n' -> escaped.append(BACKSLASH).append('n')
            '\r' -> escaped.append(BACKSLASH).append('r')
            '\t' -> escaped.append(BACKSLASH).append('t')
            else -> escaped.append(c)
        }
        escaped.append('"')
        return escaped.toString()
    }

    private const val BACKSLASH = '\u005C'
}
