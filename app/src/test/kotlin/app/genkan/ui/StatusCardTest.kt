package app.genkan.ui

import app.genkan.anki.AnkiStatus
import app.genkan.home.DualScreenStatus
import app.genkan.library.SyncStatus
import app.genkan.privileged.Privileged
import app.genkan.romm.RomMAccount
import app.genkan.romm.RomMState
import app.genkan.stats.ReadingLogStatus
import app.genkan.yomikata.CatalogStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusCardTest {

    private fun state(
        romm: RomMState = RomMState.SignedOut,
        sync: SyncStatus = SyncStatus.Idle,
        catalog: CatalogStatus = CatalogStatus.Idle,
        anki: AnkiStatus = AnkiStatus.Idle,
        readingLog: ReadingLogStatus = ReadingLogStatus.NotSet,
    ) = SetupState(
        status = DualScreenStatus(secondaryDisplayId = 4, lowerDisplayId = 4),
        romm = romm,
        sync = sync,
        usageAccess = true,
        shizuku = Privileged.State.READY,
        pspDebugger = false,
        azaharRpc = false,
        builtInPlayer = true,
        textSamples = false,
        dictionaries = emptyList(),
        catalog = catalog,
        anki = anki,
        readingLog = readingLog,
        nowMs = 10 * HOUR,
    )

    private fun line(state: SetupState, label: String) = statusLines(state).single { it.label == label }

    @Test
    fun `every connection and state has a line, in order`() {
        assertEquals(
            listOf("RomM", "Library", "Built-in player", "Yomikata", "AnkiConnect", "kechimochi", "Second screen", "Companion"),
            statusLines(state()).map { it.label },
        )
    }

    @Test
    fun `the account and the library as last seen`() {
        val account = RomMAccount(baseUrl = "https://romm.example.com/", token = "t", deviceId = null, userId = 1, username = "player1", serverVersion = "4.0.0")
        assertEquals(StatusLine("RomM", "player1 on romm.example.com"), line(state(romm = RomMState.SignedIn(account)), "RomM"))
        assertTrue(line(state(), "RomM").attention)
        assertEquals(StatusLine("Library", "64 games, synced"), line(state(sync = SyncStatus.Done(64)), "Library"))
        assertTrue(line(state(sync = SyncStatus.Failed("timeout")), "Library").attention)
    }

    @Test
    fun `the servers, reachable or not, and the catalog's age`() {
        assertEquals("120 games, updated 3 h ago", line(state(catalog = CatalogStatus.Ready(120, 7 * HOUR)), "Yomikata").value)
        assertTrue(line(state(catalog = CatalogStatus.Failed("refused")), "Yomikata").attention)
        assertEquals("Reachable, 5 cards sent", line(state(anki = AnkiStatus.Sent(5)), "AnkiConnect").value)
        assertTrue(line(state(anki = AnkiStatus.Unreachable("refused")), "AnkiConnect").attention)
        assertTrue(line(state(readingLog = ReadingLogStatus.Failed("refused")), "kechimochi").attention)
        assertFalse(line(state(readingLog = ReadingLogStatus.UpToDate), "kechimochi").attention)
    }

    @Test
    fun `AnkiConnect's line is what Settings found when it asked, else the last send`() {
        assertEquals("Reachable (AnkiConnect 6)", line(state().copy(ankiReach = AnkiReach.Reachable(6)), "AnkiConnect").value)
        assertTrue(line(state(anki = AnkiStatus.Sent(5)).copy(ankiReach = AnkiReach.Unreachable("refused")), "AnkiConnect").attention)
        assertEquals("No server set", line(state().copy(ankiReach = AnkiReach.NotSet), "AnkiConnect").value)
        assertEquals("Reachable, 5 cards sent", line(state(anki = AnkiStatus.Sent(5)), "AnkiConnect").value)
    }

    @Test
    fun `ages read in minutes, hours, then days`() {
        assertEquals("just now", age(30_000))
        assertEquals("12 min ago", age(12 * 60_000L))
        assertEquals("30 h ago", age(30 * HOUR))
        assertEquals("3 days ago", age(72 * HOUR))
        assertEquals("just now", age(-5_000))
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
