package app.genkan.bluetooth

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import app.genkan.privileged.Privileged
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The headphones Genkan can reconnect: the bonded Bluetooth audio devices. Android lets no app
 * connect one, so a tap goes the first way that's open ([ConnectRoute]): Shizuku's helper connects
 * it as Settings would; else Bluetooth settings, the device one tap away there. A tap on one that's
 * already connected opens the system's output picker, to send the sound to it.
 */
@Singleton
class Headphones @Inject constructor(
    @ApplicationContext private val context: Context,
    private val privileged: Privileged,
) {
    data class Device(val address: String, val name: String, val connected: Boolean)

    sealed interface Status {
        data object Idle : Status
        data class Connecting(val name: String) : Status
        data class Connected(val name: String) : Status
        /** Connecting wasn't open to Genkan: [where] is up for the person to pick the device. */
        data class Opened(val name: String, val where: ConnectRoute.Step) : Status
        data class Failed(val name: String) : Status
    }

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var a2dp: BluetoothA2dp? = null

    /** Debug builds' test of the fallbacks: Shizuku's connect counts as refused, Shizuku left running. */
    @Volatile var skipShizuku = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Bluetooth's word that a device connected or disconnected, while a list is open. */
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { refresh() }
        }
    }
    private var watching = 0

    /** A list opened: its rows follow Bluetooth from now until [stopWatching]. */
    @Synchronized
    fun watch() {
        if (watching++ > 0) return
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(changes, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    @Synchronized
    fun stopWatching() {
        if (watching == 0 || --watching > 0) return
        context.unregisterReceiver(changes)
    }

    /** Whether Genkan may see the bonded devices (BLUETOOTH_CONNECT, asked on first use). */
    fun allowed(): Boolean = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Reads the bonded audio devices and which are connected again. */
    suspend fun refresh() {
        if (!allowed()) return
        val adapter = adapter ?: return
        val connected = try {
            proxy()?.connectedDevices.orEmpty().map { it.address }.toSet()
        } catch (e: SecurityException) {
            emptySet()
        }
        _devices.value = try {
            adapter.bondedDevices.orEmpty()
                .filter { isAudio(it.bluetoothClass?.majorDeviceClass) }
                .map { Device(it.address, it.name ?: it.address, it.address in connected) }
                .sortedWith(compareByDescending<Device> { it.connected }.thenBy { it.name })
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /**
     * Connects [device], or opens the way the person can (see [ConnectRoute]). Bluetooth settings
     * open on [displayId], the screen the list is on, so Back returns to it.
     */
    suspend fun connect(device: Device, displayId: Int? = null) {
        // The row may be out of date (connected or not since the list last read it).
        refresh()
        val connected = _devices.value.firstOrNull { it.address == device.address }?.connected ?: device.connected
        _status.value = Status.Connecting(device.name)
        val picker = pickerIntent().takeIf { context.packageManager.queryBroadcastReceivers(it, 0).isNotEmpty() }
        val shizuku = !skipShizuku && privileged.state() == Privileged.State.READY
        for (step in ConnectRoute.steps(shizuku, picker != null, connected)) {
            when (step) {
                ConnectRoute.Step.SHIZUKU -> {
                    val code = try {
                        privileged.files()?.connectBluetooth(device.address)
                    } catch (e: Exception) {
                        null
                    }
                    Log.i(TAG, "Shizuku's connect: $code")
                    if (code == 0 && connectedWithin(device.address, CONNECT_WAIT_MS)) {
                        refresh()
                        _status.value = Status.Connected(device.name)
                        return
                    }
                }
                ConnectRoute.Step.OUTPUT_PICKER -> {
                    context.sendBroadcast(picker!!)
                    _status.value = Status.Opened(device.name, step)
                    return
                }
                ConnectRoute.Step.BLUETOOTH_SETTINGS -> {
                    openSettings(displayId)
                    _status.value = Status.Opened(device.name, step)
                    return
                }
            }
        }
        _status.value = Status.Failed(device.name)
    }

    private fun openSettings(displayId: Int?) {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = displayId?.let { android.app.ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        try {
            context.startActivity(intent, options)
        } catch (e: SecurityException) {
            // Android may keep Settings off a display; the top screen then.
            Log.w(TAG, "Bluetooth settings refused on display $displayId", e)
            context.startActivity(intent)
        }
    }

    /** The list closed: its last word is forgotten. */
    fun clearStatus() {
        _status.value = Status.Idle
    }

    private suspend fun connectedWithin(address: String, ms: Long): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            val state = try {
                proxy()?.connectedDevices.orEmpty().any { it.address == address }
            } catch (e: SecurityException) {
                false
            }
            if (state) return true
            delay(POLL_MS)
        }
        return false
    }

    private suspend fun proxy(): BluetoothA2dp? {
        a2dp?.let { return it }
        val adapter = adapter ?: return null
        return suspendCancellableCoroutine { cont ->
            val asked = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    a2dp = proxy as BluetoothA2dp
                    if (cont.isActive) cont.resume(proxy)
                }

                override fun onServiceDisconnected(profile: Int) {
                    a2dp = null
                }
            }, BluetoothProfile.A2DP)
            if (!asked && cont.isActive) cont.resume(null)
        }
    }

    private fun pickerIntent() = Intent(OUTPUT_PICKER_ACTION)
        .setPackage(SYSTEM_UI)
        .putExtra(OUTPUT_PICKER_PACKAGE, context.packageName)

    companion object {
        private const val TAG = "Headphones"
        private const val CONNECT_WAIT_MS = 10_000L
        private const val POLL_MS = 250L

        /** SystemUI's output picker (what androidx.mediarouter's output switcher sends too). */
        private const val OUTPUT_PICKER_ACTION = "com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG"
        private const val OUTPUT_PICKER_PACKAGE = "package_name"
        private const val SYSTEM_UI = "com.android.systemui"

        /** Headphones, earbuds and speakers: the audio/video class, or a device that gives none. */
        fun isAudio(majorClass: Int?): Boolean = majorClass == null || majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO
    }
}

/** The ways a tap on the headphones goes, in order, each tried only when it's open. */
object ConnectRoute {
    enum class Step { SHIZUKU, OUTPUT_PICKER, BLUETOOTH_SETTINGS }

    /**
     * For headphones not [connected]: Shizuku's helper connects them outright when Shizuku is
     * running and Genkan allowed; else Bluetooth settings, where a paired device is one tap to
     * connect. (The output picker lists only what's connected, so it can't reconnect them.) For
     * connected ones, the output picker sends the sound to them; Bluetooth settings without it.
     */
    fun steps(shizukuReady: Boolean, pickerAvailable: Boolean, connected: Boolean): List<Step> = buildList {
        if (connected) {
            if (pickerAvailable) add(Step.OUTPUT_PICKER)
        } else if (shizukuReady) {
            add(Step.SHIZUKU)
        }
        add(Step.BLUETOOTH_SETTINGS)
    }
}
