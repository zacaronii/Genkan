package app.genkan.display

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayFilterTest {

    private val own = "app.genkan"
    private fun display(id: Int, type: Int?, flags: Int = 0, owner: String? = null, on: Boolean = true) =
        DisplayInfo(id, type, flags, owner, on)

    private val top = display(0, DisplayFilter.TYPE_BUILT_IN)
    private val bottom = display(2, DisplayFilter.TYPE_BUILT_IN, flags = Display.FLAG_PRESENTATION)

    @Test
    fun `the Thor's presentation-flagged bottom panel is a screen`() {
        assertEquals(listOf(0, 2), DisplayFilter.screens(listOf(top, bottom), own).map { it.displayId })
    }

    @Test
    fun `an emulator app's virtual display and our own capture display are not screens`() {
        val azahar = display(5, DisplayFilter.TYPE_VIRTUAL, owner = "io.github.lime3ds.android")
        val capture = display(6, DisplayFilter.TYPE_VIRTUAL, owner = own)
        val screens = DisplayFilter.screens(listOf(top, bottom, azahar, capture), own, allowEmulatorDisplays = true)
        assertEquals(listOf(0, 2), screens.map { it.displayId })
        val ownerless = display(7, DisplayFilter.TYPE_VIRTUAL)
        assertEquals(listOf(0), DisplayFilter.screens(listOf(top, ownerless), own, allowEmulatorDisplays = true).map { it.displayId })
    }

    @Test
    fun `overlay and private displays never count`() {
        val overlay = display(3, 4)
        val private = display(4, DisplayFilter.TYPE_EXTERNAL, flags = Display.FLAG_PRIVATE)
        assertEquals(listOf(0), DisplayFilter.screens(listOf(top, overlay, private), own).map { it.displayId })
    }

    @Test
    fun `the emulator's multidisplay screen counts only in emulator mode`() {
        val emulatorSecond = display(1, DisplayFilter.TYPE_VIRTUAL, owner = DisplayFilter.EMULATOR_DISPLAY_OWNER)
        assertEquals(listOf(0), DisplayFilter.screens(listOf(top, emulatorSecond), own).map { it.displayId })
        assertEquals(
            listOf(0, 1),
            DisplayFilter.screens(listOf(top, emulatorSecond), own, allowEmulatorDisplays = true).map { it.displayId }
        )
    }

    @Test
    fun `the secondary display is the first lit screen that is not the default`() {
        assertEquals(2, DisplayFilter.secondaryDisplayId(listOf(top, bottom)))
        assertNull(DisplayFilter.secondaryDisplayId(listOf(top, display(2, DisplayFilter.TYPE_BUILT_IN, on = false))))
        assertNull(DisplayFilter.secondaryDisplayId(listOf(top)))
    }
}
