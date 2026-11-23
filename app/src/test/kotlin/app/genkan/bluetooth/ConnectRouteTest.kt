package app.genkan.bluetooth

import android.bluetooth.BluetoothClass
import app.genkan.bluetooth.ConnectRoute.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectRouteTest {
    @Test
    fun `Shizuku connects first, then the output picker, then Bluetooth settings`() {
        assertEquals(listOf(Step.SHIZUKU, Step.OUTPUT_PICKER, Step.BLUETOOTH_SETTINGS), ConnectRoute.steps(shizukuReady = true, pickerAvailable = true))
    }

    @Test
    fun `without Shizuku a tap opens the output picker`() {
        assertEquals(listOf(Step.OUTPUT_PICKER, Step.BLUETOOTH_SETTINGS), ConnectRoute.steps(shizukuReady = false, pickerAvailable = true))
    }

    @Test
    fun `Bluetooth settings are always the last way`() {
        assertEquals(listOf(Step.SHIZUKU, Step.BLUETOOTH_SETTINGS), ConnectRoute.steps(shizukuReady = true, pickerAvailable = false))
        assertEquals(listOf(Step.BLUETOOTH_SETTINGS), ConnectRoute.steps(shizukuReady = false, pickerAvailable = false))
    }

    @Test
    fun `audio devices, and ones that give no class, are listed`() {
        assertTrue(Headphones.isAudio(BluetoothClass.Device.Major.AUDIO_VIDEO))
        assertTrue(Headphones.isAudio(null))
        assertFalse(Headphones.isAudio(BluetoothClass.Device.Major.PHONE))
        assertFalse(Headphones.isAudio(BluetoothClass.Device.Major.PERIPHERAL))
    }
}
