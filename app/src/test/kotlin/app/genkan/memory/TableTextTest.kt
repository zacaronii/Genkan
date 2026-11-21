package app.genkan.memory

import app.genkan.yomikata.CharTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TableTextTest {

    // Human's codes (Yomikata tables/human-kana.tbl): kana in gojuon order from 0x0050, high byte first.
    private val table = CharTable.parse(
        listOf(
            "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"
                .mapIndexed { i, c -> "%04X=%c".format(0x50 + i, c) }.joinToString("\n") +
                "\n0088=だ\n008D=ば\n009C=ゃ\n009F=っ\n0003=・\n",
        ),
    )

    private fun codes(vararg parts: Any): ByteArray = parts.flatMap { part ->
        when (part) {
            is String -> part.map { c -> listOf(0x50 + "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん".indexOf(c).also { require(it >= 0) { "$c" } }) }
                .flatten().map { it }.let { list -> list.flatMap { listOf((it shr 8).toByte(), it.toByte()) } }
            is Int -> listOf((part shr 8).toByte(), part.toByte())
            else -> error(part)
        }
    }.toByteArray()

    // Moonlight Syndrome's first line: 「・・・あんなのだったら / もっと早く帰ってくればよかった」.
    private val line = codes(0xFFFA, 0x0001, 0x0003, 0x0003, 0x0003, "あんなの", 0x0088, 0x009F, "たら", 0xFFFE,
        "も", 0x009F, "と", 0x0104, "く", 0x0105, 0x009F, "てくれ", 0x008D, "よか", 0x009F, "た", 0x0042, 0xFFFF)

    @Test
    fun `the kana come from the table and the kanji from OCR`() {
        val reader = TableText(table)
        val ram = byteArrayOf(0x11) + line + ByteArray(8) // odd address, as in the game's buffer
        val ocr = "o.あんなのだったらもっと早く帰ってくればよなった▼"
        val probe = reader.probes(ocr).first()
        val hit = MemoryScan.find(ram, probe).single()
        assertEquals(1, hit % 2)
        val tokens = reader.tokens(ram, 1, ram.size)
        assertEquals("・・・あんなのだったらもっと早く帰ってくればよかった", reader.correct(tokens, ocr))
        assertEquals(mapOf("0104" to '早', "0105" to '帰'), reader.learned()) // ▼ isn't learned for 0042
    }

    @Test
    fun `kanji two lines agreed on beat a misreading`() {
        val reader = TableText(table)
        val tokens = reader.tokens(line, 0, line.size)
        reader.correct(tokens, "あんなのだったらもっと早く帰ってくればよかった")
        reader.correct(tokens, "あんなのだったらもっと早く帰ってくればよかった")
        assertEquals("・・・あんなのだったらもっと早く帰ってくればよかった", reader.correct(tokens, "あんなのだったらもっと旱く帰ってくればよかった"))
    }

    @Test
    fun `a little-endian table's controls end its lines`() {
        // Twilight Syndrome's codes (Yomikata tables/ps1/SLPS-00102.tbl): あ = 01 00, controls 0x8xxx.
        val kana = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"
        val twilight = CharTable.parse(listOf(kana.mapIndexed { i, c -> "%02X00=%c".format(1 + i, c) }.joinToString("\n")))
        fun le(text: String) = text.flatMap { c -> listOf((kana.indexOf(c) + 1).toByte(), 0) }.toByteArray()
        val ram = le("ほかのせりふ") + byteArrayOf(0x00, 0x81.toByte()) + le("うしろから") + byteArrayOf(0x22, 0x01) + le("こえてくる") + byteArrayOf(0x08, 0x84.toByte()) + le("つぎのせりふ")
        val reader = TableText(twilight)
        assertEquals("うしろから聞こえてくる", reader.correct(reader.tokens(ram, 0, ram.size), "うしろから聞こえてくる"))
    }

    @Test
    fun `other text isn't forced onto the codes`() {
        val reader = TableText(table)
        assertNull(reader.correct(reader.tokens(line, 0, line.size), "まったくちがうせりふをいう"))
    }
}
