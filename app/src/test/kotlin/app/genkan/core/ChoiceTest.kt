package app.genkan.core

import app.genkan.yomikata.LayoutMemory
import app.genkan.yomikata.LayoutRead
import app.genkan.yomikata.Layouts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChoiceTest {
    private val up = LayoutRead(message = "：右\n：左", choosing = true, choices = listOf("右", "左"), choiceCursor = 1)

    @Test
    fun `a choice up gives its answers and the cursor row`() {
        assertEquals(BuiltInText.Choice(listOf("右", "左"), 1), BuiltInText.Choice.of(up))
    }

    @Test
    fun `none once the read fails, the choice ends or the layout stops`() {
        // The choosing cell couldn't be read: no flag, an error instead.
        assertNull(BuiltInText.Choice.of(up.copy(choosing = null, errors = mapOf("choosing" to "outside memory"))))
        assertNull(BuiltInText.Choice.of(up.copy(choosing = false)))
        assertNull(BuiltInText.Choice.of(up.copy(choices = emptyList())))
        assertNull(BuiltInText.Choice.of(null))
    }

    @Test
    fun `a layout whose when turns false gives no choice`() {
        val layout = JSONObject(
            """{"when": {"address": "0x0", "equals": 1}, "choosing": {"address": "0x1"},
            "choices": {"address": "0x2", "charset": "utf8", "max_bytes": 4}}""",
        )
        // when false (0), though the choosing cell is set and an answer is in memory.
        val memory = LayoutMemory { address, size -> byteArrayOf(0, 1, 'A'.code.toByte(), 0, 0, 0).copyOfRange(address.toInt(), address.toInt() + size) }
        assertNull(BuiltInText.Choice.of(Layouts.read(layout, memory, 2, null, emptyMap(), emptyList())))
    }
}
