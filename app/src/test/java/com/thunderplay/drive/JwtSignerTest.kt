package com.thunderplay.drive

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class JwtSignerTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()

    private fun pem(): String {
        val body = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n" + body.chunked(64).joinToString("\n") +
            "\n-----END PRIVATE KEY-----\n"
    }

    private fun key() = ServiceAccountKey(
        clientEmail = "thunder@example.iam.gserviceaccount.com",
        privateKey = pem(),
        privateKeyId = "abc123",
    )

    private fun decode(segment: String) = String(Base64.getUrlDecoder().decode(segment))

    @Test
    fun `assertion has three base64url segments`() {
        val jwt = JwtSigner.buildAssertion(key(), nowSeconds = 1_700_000_000)
        assertThat(jwt.split(".")).hasSize(3)
        // URL-safe alphabet only: '+' and '/' would break the token endpoint.
        assertThat(jwt).doesNotContain("+")
        assertThat(jwt).doesNotContain("/")
        assertThat(jwt).doesNotContain("=")
    }

    @Test
    fun `header and claims carry the expected fields`() {
        val now = 1_700_000_000L
        val jwt = JwtSigner.buildAssertion(key(), nowSeconds = now)
        val (headerSeg, claimsSeg) = jwt.split(".").let { it[0] to it[1] }

        val header = decode(headerSeg)
        assertThat(header).contains("\"alg\":\"RS256\"")
        assertThat(header).contains("\"kid\":\"abc123\"")

        val claims = decode(claimsSeg)
        assertThat(claims).contains("\"iss\":\"thunder@example.iam.gserviceaccount.com\"")
        assertThat(claims).contains("\"scope\":\"${JwtSigner.DRIVE_SCOPE}\"")
        assertThat(claims).contains("\"aud\":\"https://oauth2.googleapis.com/token\"")
        assertThat(claims).contains("\"iat\":$now")
        assertThat(claims).contains("\"exp\":${now + 3600}")
    }

    @Test
    fun `signature verifies against the public key`() {
        val jwt = JwtSigner.buildAssertion(key(), nowSeconds = 1_700_000_000)
        val parts = jwt.split(".")
        val signingInput = "${parts[0]}.${parts[1]}"

        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(keyPair.public)
            update(signingInput.toByteArray())
            verify(Base64.getUrlDecoder().decode(parts[2]))
        }
        assertThat(verified).isTrue()
    }

    @Test
    fun `parsePrivateKey accepts a pem with headers and line breaks`() {
        val parsed = JwtSigner.parsePrivateKey(pem())
        assertThat(parsed.algorithm).isEqualTo("RSA")
        assertThat(parsed.encoded).isEqualTo(keyPair.private.encoded)
    }

    @Test
    fun `quoting escapes characters that would corrupt the claim set`() {
        val backslash = '\u005C'
        val quote = '\u0022'
        // A client_email containing a quote or backslash must not break out of the JSON string.
        val messy = "we" + quote + "ird" + backslash + "name@example.com"
        val jwt = JwtSigner.buildAssertion(key().copy(clientEmail = messy), nowSeconds = 1L)
        val claims = decode(jwt.split(".")[1])

        assertThat(claims).contains("we" + backslash + quote + "ird")
        assertThat(claims).contains(backslash.toString() + backslash + "name")
    }
}
