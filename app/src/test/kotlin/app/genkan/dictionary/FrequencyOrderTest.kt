package app.genkan.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class FrequencyOrderTest {
    @Test
    fun `a rank list sorts the lowest first, as do lists that give no mode`() {
        assertEquals(FrequencyOrder.ASCENDING, FrequencyOrder.of(hostOrder = null, mode = "rank-based"))
        assertEquals(FrequencyOrder.ASCENDING, FrequencyOrder.of(hostOrder = null, mode = null))
    }

    @Test
    fun `a count of occurrences sorts the highest first`() {
        assertEquals(FrequencyOrder.DESCENDING, FrequencyOrder.of(hostOrder = null, mode = "occurrence-based"))
    }

    @Test
    fun `a linked Hachidori's own order is used as it is`() {
        assertEquals(FrequencyOrder.AUTO, FrequencyOrder.of("auto", mode = null))
        assertEquals(FrequencyOrder.DESCENDING, FrequencyOrder.of("descending", mode = "rank-based"))
        assertEquals(FrequencyOrder.DISABLED, FrequencyOrder.of("disabled", mode = "rank-based"))
        assertEquals(FrequencyOrder.ASCENDING, FrequencyOrder.of("something new", mode = null))
    }

    @Test
    fun `the values are hoshidicts' own`() {
        assertEquals(listOf(0, 1, 2, 3), FrequencyOrder.entries.map { it.native })
    }
}
