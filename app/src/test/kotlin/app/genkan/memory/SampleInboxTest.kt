package app.genkan.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleInboxTest {
    @Test
    fun `a refused snapshot stays on the device, for size or for what it is`() {
        assertTrue(SampleInbox.keepsRefused(413))
        // A meta the inbox doesn't take yet (ocr_unavailable before it knew the field) is kept too.
        assertTrue(SampleInbox.keepsRefused(400))
        assertFalse(SampleInbox.keepsRefused(500))
        assertFalse(SampleInbox.keepsRefused(200))
    }

    @Test
    fun `a held snapshot waits for the Yomikata page's send`() {
        val held = setOf("NPJH50372-1.meta.json")
        assertTrue(SampleInbox.skipsHeld("NPJH50372-1.meta.json", held, retryHeld = false))
        assertFalse(SampleInbox.skipsHeld("NPJH50372-1.meta.json", held, retryHeld = true))
        assertFalse(SampleInbox.skipsHeld("NPJH50372-2.meta.json", held, retryHeld = false))
    }

    @Test
    fun `the reason shown is the inbox's detail`() {
        assertEquals("state is larger than 32 MB", SampleInbox.detailOf("""{"detail":"state is larger than 32 MB"}"""))
        assertEquals("Request Entity Too Large", SampleInbox.detailOf("Request Entity Too Large"))
    }
}
