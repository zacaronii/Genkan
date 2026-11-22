package app.genkan.yomikata

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The release id learned for a game is kept by its ROM id: another game never gets it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class YomikataIdsTest {
    private val ids = YomikataIds(ApplicationProvider.getApplicationContext())

    @Test
    fun `an id learned for one rom is that rom's alone`() = runBlocking {
        ids.learned(326, "SLPS-01100")
        ids.learned(311, "SCPS-10088")
        assertEquals("SLPS-01100", ids.idFor(326).first())
        assertEquals("SCPS-10088", ids.idFor(311).first())
        // Another game in the same core, or a rom never learned, has none.
        assertNull(ids.idFor(325).first())
    }

    @Test
    fun `a game with no rom id learns nothing`() = runBlocking {
        ids.learned(null, "SLPS-02100")
        assertNull(ids.idFor(0).first())
    }
}
