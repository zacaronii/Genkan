package app.genkan.stats

import app.genkan.launch.LocalShelves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReadingLogPayloadTest {

    private fun session(platform: String?, rom: Long?, ended: Long? = null) = ReadingSessionTotals(
        id = 7, game = "game:1", title = "漢検トレーニング2", platform = platform, rommRomId = rom,
        started = 1_700_000_000_000, lastActivity = 1_700_000_600_000, ended = ended, pushed = null,
        activeMs = 61_500, characters = 1234,
    )

    @Test
    fun `a game posts in mochibridge's generic format`() {
        val body = ReadingLogPusher.payload(session("3ds", 1), now = 1_700_000_660_000)
        assertEquals("genkan", body["client"])
        assertEquals("genkan-7-1700000000000", body["session_id"])
        assertEquals("2023-11-14T22:13:20Z", body["started_at"])
        assertEquals(61L, body["seconds"])
        assertEquals(1234L, body["characters"])
        assertEquals(false, body["final"])
        assertEquals("Videogame", body["content_type"])
        assertEquals("Playing", body["activity_type"])
        assertEquals("ja", body["language"])
        assertEquals(
            mapOf("title" to "漢検トレーニング2", "ids" to mapOf("romm.rom_id" to 1L), "fields" to mapOf("Platform" to "3ds")),
            body["entry"],
        )
        assertFalse("kind" in body || "title" in body || "romm_rom_id" in body)
    }

    @Test
    fun `a PC game is a visual novel, read, and an unknown rom has no ids`() {
        val body = ReadingLogPusher.payload(session(LocalShelves.PC_SLUG, null, ended = 1L), now = 0)
        assertEquals("Visual Novel", body["content_type"])
        assertEquals("Reading", body["activity_type"])
        assertEquals(true, body["final"])
        assertEquals(mapOf("title" to "漢検トレーニング2", "fields" to mapOf("Platform" to "PC")), body["entry"])
    }
}
