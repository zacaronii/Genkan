package app.genkan.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RomMApiFactoryTest {

    @Test
    fun `a bare host becomes an https base url with a trailing slash`() {
        assertEquals("https://romm.example.com/", RomMApiFactory.normalizeBaseUrl("romm.example.com"))
        assertEquals("https://romm.example.com/", RomMApiFactory.normalizeBaseUrl(" https://romm.example.com/ "))
        assertEquals("http://10.0.0.5:8080/", RomMApiFactory.normalizeBaseUrl("http://10.0.0.5:8080"))
    }

    @Test
    fun `asset paths are joined to the server and their spaces encoded`() {
        assertEquals(
            "https://romm.example.com/assets/romm/resources/roms/14/234/cover/small.png?ts=2026-10-05%2001:54:31",
            RomMApiFactory.assetUrl("https://romm.example.com/", "/assets/romm/resources/roms/14/234/cover/small.png?ts=2026-10-05 01:54:31"),
        )
    }

    @Test
    fun `absolute urls pass through and blanks give nothing`() {
        assertEquals("https://cdn.example.com/a.png", RomMApiFactory.assetUrl("https://romm.example.com/", "https://cdn.example.com/a.png"))
        assertNull(RomMApiFactory.assetUrl("https://romm.example.com/", null))
        assertNull(RomMApiFactory.assetUrl("https://romm.example.com/", ""))
    }

    @Test
    fun `device-flow error details are read from the body`() {
        assertEquals("authorization_pending", RomMSession.parseDetail("""{"detail":"authorization_pending"}"""))
        assertNull(RomMSession.parseDetail("<html>bad gateway</html>"))
        assertNull(RomMSession.parseDetail(null))
    }

    @Test
    fun `version gates`() {
        assertEquals(false, RomMCapabilities.from("4.9.0").isSupportedVersion)
        assertEquals(true, RomMCapabilities.from("5.4.0").supportsDeviceAuth)
        assertEquals(RomMCapabilities.NONE, RomMCapabilities.from(null))
    }
}
