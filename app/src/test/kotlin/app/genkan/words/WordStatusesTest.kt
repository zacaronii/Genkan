package app.genkan.words

import org.junit.Assert.assertEquals
import org.junit.Test

class WordStatusesTest {

    @Test
    fun `headwords are keyed as Hachidori keys them`() {
        // か + combining dakuten, as some text sources write が, and stray spaces.
        assertEquals("学ぶ", WordStatuses.key("  学ぶ\n"))
        assertEquals("が", WordStatuses.key("が"))
    }
}
