package app.genkan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TuningStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a game's own step is kept, read again from the file, and cleared`() {
        val dir = File(folder.root, "tuning")
        TuningStore(dir).setOverride(42, "efb-2x", "learning", atMs = 1_000)
        TuningStore(dir).setOverride(7, "default", "reset", atMs = 2_000)
        assertEquals(TuningStore.Override("efb-2x", 1_000, "learning"), TuningStore(dir).override(42))
        TuningStore(dir).clearOverride(42)
        assertNull(TuningStore(dir).override(42))
        assertEquals("default", TuningStore(dir).override(7)?.step)
    }

    @Test
    fun `no file, or one that doesn't read, is no steps`() {
        val dir = File(folder.root, "tuning").apply { mkdirs() }
        assertNull(TuningStore(dir).override(42))
        File(dir, TuningStore.FILE).writeText("{not json")
        assertNull(TuningStore(dir).override(42))
    }
}
