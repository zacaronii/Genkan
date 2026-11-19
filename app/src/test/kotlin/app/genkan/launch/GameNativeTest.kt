package app.genkan.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameNativeTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `markers name the game and hold its Steam app id`() {
        val dir = tmp.newFolder("Steam")
        File(dir, "Marco & The Galaxy Dragon.steam").writeText("1202540\n")
        File(dir, "Aokana.steam").writeText("1044620")
        File(dir, "inventory.json").writeText("{}")
        File(dir, "Broken.steam").writeText("not a number")
        File(dir, "Huge.steam").writeText("1".repeat(200))
        val games = GameNative.readMarkers(dir)
        assertEquals(listOf(PcGame(1044620, "Aokana"), PcGame(1202540, "Marco & The Galaxy Dragon")), games)
        assertTrue(games[1].coverUrl.endsWith("/1202540/library_600x900.jpg"))
    }

    @Test
    fun `ids must be positive and fit 32 bits`() {
        assertEquals(42, GameNative.parseId(" 42 "))
        assertNull(GameNative.parseId("0"))
        assertNull(GameNative.parseId("-5"))
        assertNull(GameNative.parseId("99999999999"))
    }

    @Test
    fun `a missing folder is an empty shelf`() {
        assertEquals(emptyList<PcGame>(), GameNative.readMarkers(File(tmp.root, "nope")))
    }
}
