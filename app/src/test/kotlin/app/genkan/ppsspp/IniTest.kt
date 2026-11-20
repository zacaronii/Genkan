package app.genkan.ppsspp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IniTest {

    private val ini = "\uFEFF[General]\nFirstRun = False\nRemoteISOPort = 0\nRemoteDebuggerOnStartup = False\n\n[CPU]\nJit = True\n"

    @Test
    fun `values are read and replaced in their own section only`() {
        assertEquals("0", Ini.get(ini, "General", "RemoteISOPort"))
        assertNull(Ini.get(ini, "CPU", "RemoteISOPort"))
        val on = Ini.set(Ini.set(ini, "General", "RemoteISOPort", "45099"), "General", "RemoteDebuggerOnStartup", "True")
        assertEquals("45099", Ini.get(on, "General", "RemoteISOPort"))
        assertEquals("True", Ini.get(on, "General", "RemoteDebuggerOnStartup"))
        assertEquals("True", Ini.get(on, "CPU", "Jit"))
        assertEquals(ini.lines().size, on.lines().size)
    }

    @Test
    fun `missing keys and sections are added`() {
        val added = Ini.set(ini, "General", "NewKey", "1")
        assertEquals("1", Ini.get(added, "General", "NewKey"))
        assertEquals("[General]", added.lines()[0].removePrefix("\uFEFF"))
        assertEquals("NewKey = 1", added.lines()[4])
        val made = Ini.set("", "General", "RemoteISOPort", "45099")
        assertEquals("[General]\nRemoteISOPort = 45099\n", made)
    }

    @Test
    fun `windows line endings stay`() {
        val crlf = "[General]\r\nRemoteISOPort = 0\r\n"
        assertEquals("[General]\r\nRemoteISOPort = 45099\r\n", Ini.set(crlf, "General", "RemoteISOPort", "45099"))
    }
}
