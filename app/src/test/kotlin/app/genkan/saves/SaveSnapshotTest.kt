package app.genkan.saves

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveSnapshotTest {

    @Test
    fun `a session's save is what it created or changed`() {
        val before = SaveSnapshot.parse(arrayOf("memcards/A_1.mcd\t131072\t100", "memcards/B_1.mcd\t131072\t100", "junk"))
        val after = SaveSnapshot.parse(
            arrayOf("memcards/A_1.mcd\t131072\t100", "memcards/B_1.mcd\t131072\t250", "memcards/C_1.mcd\t131072\t260"),
        )
        assertEquals(listOf("memcards/B_1.mcd", "memcards/C_1.mcd"), SaveSnapshot.changed(before, after))
        assertEquals(emptyList<String>(), SaveSnapshot.changed(after, after))
    }

    @Test
    fun `watched emulators live in their Android data folders`() {
        val duck = WatchedEmulator.BY_PLATFORM.getValue("psx")
        assertEquals("/sd/Android/data/com.github.stenzek.duckstation/files", duck.base("/sd") { false })
        // PPSSPP: the memory stick folder that has a PSP folder in it.
        val psp = WatchedEmulator.BY_PLATFORM.getValue("psp")
        assertEquals("/sd/ppsspp", psp.base("/sd") { it == "/sd/ppsspp/PSP" })
        assertEquals("/sd", psp.base("/sd") { it == "/sd/PSP" })
    }
}
