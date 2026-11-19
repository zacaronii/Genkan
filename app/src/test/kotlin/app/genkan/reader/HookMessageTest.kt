package app.genkan.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HookMessageTest {

    @Test
    fun `hookbridge frames carry their thread and hook code`() {
        val frame = """{"sentence":"窓の外を眺めた。","thread":"TextOutA","hookcode":"HS10@0:gdi32.dll:TextOutA","ctx":"4012a0","ctx2":"0"}"""
        assertEquals(HookMessage("窓の外を眺めた。", "TextOutA", "HS10@0:gdi32.dll:TextOutA"), HookMessage.parse(frame))
    }

    @Test
    fun `plain text frames have no thread`() {
        assertEquals(HookMessage("ただの行"), HookMessage.parse("ただの行"))
    }

    @Test
    fun `frames without text are dropped`() {
        assertNull(HookMessage.parse("""{"type":"status"}"""))
        assertNull(HookMessage.parse("   "))
    }
}
