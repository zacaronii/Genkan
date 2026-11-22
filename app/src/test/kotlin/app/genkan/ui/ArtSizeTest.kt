package app.genkan.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtSizeTest {
    @Test
    fun `room to spare keeps the full size`() {
        assertEquals(176.dp, artSize(600.dp, logo = true, buttonsShown = 3))
        assertEquals(176.dp, artSize(468.dp, logo = false, buttonsShown = 1))
    }

    @Test
    fun `the art gives way so three buttons fit`() {
        // 400 dp: the logo and its gap 72, then 20 between the art and Play, then 3 buttons and 2 gaps 148.
        assertEquals(160.dp, artSize(400.dp, logo = true, buttonsShown = 3))
    }

    @Test
    fun `never smaller than the floor`() {
        assertEquals(112.dp, artSize(200.dp, logo = true, buttonsShown = 3))
    }

    @Test
    fun `with more buttons to come, a gap and the fade are kept too`() {
        assertEquals(132.dp, artSize(400.dp, logo = true, buttonsShown = 3, more = true))
    }
}
