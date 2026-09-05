package com.thunderplay.drive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A bare "HTTP 400" is undiagnosable on a phone with no cable attached, so whatever Google sends
 * back has to survive into the message.
 */
class DriveApiExceptionTest {

    private val invalidQuery = """
        {"error":{"code":400,"message":"Invalid Value",
        "errors":[{"domain":"global","reason":"invalid","message":"Invalid Value"}]}}
    """.trimIndent()

    @Test
    fun `Googles own explanation reaches the message`() {
        val error = DriveApiException.from(
            status = 400,
            statusMessage = "Bad Request",
            body = invalidQuery,
            path = "/drive/v3/files",
            query = "q=name%3Dbroken",
        )
        assertThat(error.status).isEqualTo(400)
        assertThat(error.reason).isEqualTo("Invalid Value")
        assertThat(error.short).isEqualTo("Drive 400: Invalid Value")
        assertThat(error.message).contains("/drive/v3/files")
    }

    @Test
    fun `the failing query is named, so a malformed one is visible at a glance`() {
        // This is the exact shape of the bug that produced a 400 in the field: a Kotlin template
        // that never got evaluated. Seeing the query is what makes that obvious.
        val error = DriveApiException.from(
            400,
            "Bad Request",
            invalidQuery,
            "/drive/v3/files",
            "q=name%3D%24%7Bquoted(name)%7D",
        )
        assertThat(error.request).contains("quoted(name)")
    }

    @Test
    fun `a permissions failure carries its reason code`() {
        val body = """{"error":{"code":403,"message":"Insufficient permissions",
            "errors":[{"reason":"insufficientFilePermissions"}]}}"""
        val error = DriveApiException.from(403, "Forbidden", body, "/drive/v3/files/abc", null)

        assertThat(error.reason).isEqualTo("Insufficient permissions")
        assertThat(error.detail).isEqualTo("insufficientFilePermissions")
        assertThat(error.message).contains("insufficientFilePermissions")
    }

    @Test
    fun `a non-JSON body falls back to the HTTP status line`() {
        val error = DriveApiException.from(
            502,
            "Bad Gateway",
            "<html>Bad Gateway</html>",
            "/drive/v3/files",
            null,
        )
        assertThat(error.reason).isEqualTo("Bad Gateway")
    }

    @Test
    fun `an empty body still yields something printable`() {
        val error = DriveApiException.from(500, "", "", "/drive/v3/files", null)
        assertThat(error.reason).isEqualTo("unknown error")
    }

    @Test
    fun `a very long query is truncated rather than flooding the log`() {
        val long = "q=" + "a".repeat(1000)
        val error = DriveApiException.from(400, "Bad Request", invalidQuery, "/x", long)

        assertThat(error.request.length).isLessThan(400)
        assertThat(error.request).endsWith("...")
    }

    @Test
    fun `a missing field returns empty rather than throwing`() {
        assertThat(DriveApiException.field("{}", "message")).isEmpty()
        assertThat(DriveApiException.field("not json at all", "message")).isEmpty()
        assertThat(DriveApiException.field("""{"message":}""", "message")).isEmpty()
    }
}
