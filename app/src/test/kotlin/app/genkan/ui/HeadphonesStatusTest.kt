package app.genkan.ui

import app.genkan.bluetooth.ConnectRoute
import app.genkan.bluetooth.Headphones.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadphonesStatusTest {
    @Test
    fun `the list says what the last tap did`() {
        assertEquals("Connecting Buds…", headphonesStatus(Status.Connecting("Buds"), shizuku = true))
        assertEquals("Buds connected", headphonesStatus(Status.Connected("Buds"), shizuku = true))
        assertEquals("Pick Buds in the output list", headphonesStatus(Status.Opened("Buds", ConnectRoute.Step.OUTPUT_PICKER), shizuku = false))
        assertEquals("Tap Buds in Bluetooth settings", headphonesStatus(Status.Opened("Buds", ConnectRoute.Step.BLUETOOTH_SETTINGS), shizuku = false))
        assertEquals("Couldn't connect Buds", headphonesStatus(Status.Failed("Buds"), shizuku = true))
    }

    @Test
    fun `without Shizuku the list says a tap opens Android's list`() {
        assertEquals("Tap headphones to connect them.", headphonesStatus(Status.Idle, shizuku = true))
        assertTrue(headphonesStatus(Status.Idle, shizuku = false).contains("Shizuku"))
    }
}
