package app.genkan.bluetooth

import android.bluetooth.BluetoothClass
import app.genkan.bluetooth.ConnectRoute.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectRouteTest {
    @Test
    fun `headphones not connected are connected by Shizuku, else Bluetooth settings`() {
        assertEquals(listOf(Step.SHIZUKU, Step.BLUETOOTH_SETTINGS), ConnectRoute.steps(shizukuReady = true, pickerAvailable = true, connected = false))
        assertEquals(listOf(Step.BLUETOOTH_SETTINGS), ConnectRoute.steps(shizukuReady = false, pickerAvailable = true, connected = false))
    }

    @Test
    fun `the output picker, which lists only what's connected, never reconnects`() {
        for (shizuku in listOf(true, false)) {
            assertFalse(Step.OUTPUT_PICKER in ConnectRoute.steps(shizuku, pickerAvailable = true, connected = false))
        }
    }

    @Test
    fun `for connected headphones the output picker sends the sound to them`() {
        assertEquals(listOf(Step.OUTPUT_PICKER, Step.BLUETOOTH_SETTINGS), ConnectRoute.steps(shizukuReady = true, pickerAvailable = true, connected = true))
        assertEquals(listOf(Step.BLUETOOTH_SETTINGS), ConnectRoute.steps(shizukuReady = true, pickerAvailable = false, connected = true))
    }

    @Test
    fun `Bluetooth settings are always the last way`() {
        for (shizuku in listOf(true, false)) for (picker in listOf(true, false)) for (connected in listOf(true, false)) {
            assertEquals(Step.BLUETOOTH_SETTINGS, ConnectRoute.steps(shizuku, picker, connected).last())
        }
    }

    @Test
    fun `audio devices, and ones that give no class, are listed`() {
        assertTrue(Headphones.isAudio(BluetoothClass.Device.Major.AUDIO_VIDEO))
        assertTrue(Headphones.isAudio(null))
        assertFalse(Headphones.isAudio(BluetoothClass.Device.Major.PHONE))
        assertFalse(Headphones.isAudio(BluetoothClass.Device.Major.PERIPHERAL))
    }
}
