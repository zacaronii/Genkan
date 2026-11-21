package app.genkan.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleInboxTest {
    @Test
    fun `a size limit keeps the snapshot, anything else drops it`() {
        assertTrue(SampleInbox.isSizeLimit(400, """{"detail":"state is larger than 32 MB"}"""))
        assertTrue(SampleInbox.isSizeLimit(413, "Request Entity Too Large"))
        assertTrue(SampleInbox.isSizeLimit(400, """{"detail":"ram exceeds the limit"}"""))
        assertFalse(SampleInbox.isSizeLimit(400, """{"detail":"base_address is outside main RAM"}"""))
        assertFalse(SampleInbox.isSizeLimit(400, """{"detail":"meta is not valid JSON"}"""))
    }

    @Test
    fun `the reason shown is the inbox's detail`() {
        assertEquals("state is larger than 32 MB", SampleInbox.detailOf("""{"detail":"state is larger than 32 MB"}"""))
        assertEquals("Request Entity Too Large", SampleInbox.detailOf("Request Entity Too Large"))
    }
}
