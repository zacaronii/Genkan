package app.genkan.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextShapeTest {

    /** A made-up 16-bit table, as a game with its own encoding would have. */
    private fun encode(text: String, bigEndian: Boolean): ByteArray {
        val codes = HashMap<Char, Int>()
        return text.flatMap { c ->
            val v = codes.getOrPut(c) { 0x0100 + codes.size * 7 }
            if (bigEndian) listOf((v shr 8).toByte(), v.toByte()) else listOf(v.toByte(), (v shr 8).toByte())
        }.toByteArray()
    }

    @Test fun probeTakesTheStretchThatRepeatsMost() {
        assertEquals("ここにいるのはわたしたち", TextShape.probe("「ここにいるのはわたしたち」"))
        // Moonlight Syndrome: across its row break and within the second row, other tries.
        val tries = TextShape.probes("o.あんなのだったらもっと早く帰ってくればよかった▼")
        assertEquals("んなのだったらもっと早く帰ってく", tries[0])
        assertTrue(tries.any { it in "もっと早く帰ってくればよかった" })
        // Fewer than two repeats: it would match almost anywhere.
        assertNull(TextShape.probe("あいうえおか"))
        assertNull(TextShape.probe("ここにいるのはわたし"))
        assertNull(TextShape.probe("「はい」"))
    }

    @Test fun findsTheLineInAnUnknownTableEitherByteOrder() {
        val line = "ここにいるのはわたしたち"
        for (bigEndian in listOf(false, true)) {
            val noise = ByteArray(301) { (it * 37 + 11).toByte() }
            val bytes = noise + byteArrayOf(0) + encode(line, bigEndian) + noise
            val hits = TextShape.find(bytes, TextShape.probe(line)!!)
            assertTrue(hits.any { it.offset == 302 && it.unit == 2 && it.bigEndian == bigEndian })
        }
    }

    @Test fun shapeMarksRepeats() {
        assertEquals(listOf(0, 0, 1, 0), TextShape.shapeOf("ああいあ").toList())
    }
}
