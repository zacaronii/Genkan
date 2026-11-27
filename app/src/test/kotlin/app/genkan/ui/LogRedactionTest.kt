package app.genkan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRedactionTest {

    private fun redact(text: String, secrets: List<String> = emptyList()) = LogRedaction.redact(text, secrets)

    @Test
    fun `the secrets Genkan holds go wherever they appear`() {
        val out = redact("GET /api/roms?x=1 with fake-token-123456 and again fake-token-123456", listOf("fake-token-123456"))
        assertFalse("fake-token-123456" in out)
        assertEquals("GET /api/roms?x=1 with [redacted] and again [redacted]", out)
    }

    @Test
    fun `keys, tokens and Authorization headers a line gives are taken out, the rest kept`() {
        assertEquals("url ?apikey=[redacted]&page=2", redact("url ?apikey=abc123def&page=2"))
        assertEquals("Authorization: [redacted] next", redact("Authorization: Bearer eyJ.abc.def next"))
        assertEquals("token=[redacted], ok", redact("token=s3cr3t, ok"))
        assertEquals("sent with bearer [redacted]", redact("sent with bearer abcDEF123"))
        assertEquals("PlayerStats: fps 60.0 speed 100%", redact("PlayerStats: fps 60.0 speed 100%"))
    }

    @Test
    fun `a value too short to be a secret isn't one`() {
        assertEquals("a b c", redact("a b c", listOf("b")))
    }
}
