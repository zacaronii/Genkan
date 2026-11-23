package app.genkan.privileged

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Log

/**
 * Bluetooth from the Shizuku helper's process, which has the shell user's identity: it may
 * connect a bonded device ([BluetoothDevice.connect] is a system API that needs
 * BLUETOOTH_PRIVILEGED, which the shell user holds and no app can be granted).
 */
internal object ShellBluetooth {
    private const val TAG = "ShellBluetooth"

    /** The helper's process isn't an app: it has no service registry until this hands one over. */
    @Volatile private var ready = false

    private fun adapter(): BluetoothAdapter? {
        if (!ready) {
            // Set once per process; a second time throws, which is fine to ignore.
            runCatching {
                val manager = Class.forName("android.os.BluetoothServiceManager")
                val init = Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
                init.getDeclaredMethod("setBluetoothServiceManager", manager).apply { isAccessible = true }
                    .invoke(null, manager.getDeclaredConstructor().apply { isAccessible = true }.newInstance())
            }
            ready = true
        }
        @Suppress("DEPRECATION")
        return BluetoothAdapter.getDefaultAdapter()
    }

    /** Bluetooth's status code for asking [address] to connect (0: asked); -1 when it couldn't be asked. */
    fun connect(address: String): Int = try {
        val device = adapter()?.getRemoteDevice(address) ?: return -1
        BluetoothDevice::class.java.getMethod("connect").invoke(device) as Int
    } catch (e: Throwable) {
        Log.w(TAG, "Couldn't ask the device to connect", e)
        -1
    }
}
