package app.genkan.ppsspp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

class PspHooksTest {

    @Test
    fun `Dunamis15 drops ruby, colour and control codes`() {
        assertEquals("、陸から来た", PspHooks.dunamis15("、%0#0077ff77;[おか]陸%0#;から\\k来た"))
        assertEquals("一行目二行目", PspHooks.dunamis15("一行目\n二行目"))
    }

    @Test
    fun `strings end at their terminator, in the game's encoding`() {
        val sjis = Charset.forName("Shift_JIS")
        val bytes = "放課後".toByteArray(sjis) + byteArrayOf(0, 0x41, 0x42)
        assertEquals("放課後", PspHooks.decode(bytes, sjis))
        assertEquals("abc", PspHooks.decode("abc".toByteArray(), Charsets.UTF_8))
    }

    @Test
    fun `Tokimeki's choice hook keeps only lines that aren't sentences`() {
        val choices = PspHooks.byGameId.getValue("NPJH50127")[2]
        assertEquals("一緒に帰る", choices.clean("一緒に帰る"))
        assertNull(choices.clean("今日はいい天気だね。"))
    }

    @Test
    fun `UTF-16 strings end at a two-byte terminator`() {
        val bytes = "希望の学園".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0, 0x41, 0)
        assertEquals("希望の学園", PspHooks.decode(bytes, Charsets.UTF_16LE))
        // A zero high byte inside a character isn't a terminator.
        assertEquals("A", PspHooks.decode(byteArrayOf(0x41, 0, 0, 0), Charsets.UTF_16LE))
    }

    @Test
    fun `ported LunaHook filters`() {
        assertEquals("ちょっと待ってー", PspHooks.myMerryMay("%Kちょっと%N待って├%P"))
        assertEquals("希望の学園", PspHooks.danganronpa("<CLT 3>希望の\n学園<CLT>"))
        // The game keeps hiragana as half-width katakana; ﾞ stands for ！ in its font.
        assertEquals("「こんにちは。」", PspHooks.grisaia("｢ｺﾝﾆﾁﾊ｡｣"))
        assertEquals("天音です", PspHooks.grisaia("\$t天音@です"))
    }
}
