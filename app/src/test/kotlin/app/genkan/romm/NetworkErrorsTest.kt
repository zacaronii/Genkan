package app.genkan.romm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class NetworkErrorsTest {

    @Test
    fun `an unresolvable host is named as a network problem`() {
        assertEquals(
            "Can't find the RomM server from this network",
            describeFailure(UnknownHostException("Unable to resolve host \"romm.example\""), "Download failed"),
        )
    }

    @Test
    fun `other errors keep their message, or the fallback`() {
        assertEquals("Size mismatch", describeFailure(IOException("Size mismatch"), "Download failed"))
        assertEquals("Download failed", describeFailure(IOException(), "Download failed"))
    }
}
