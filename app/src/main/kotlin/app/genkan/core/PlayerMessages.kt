package app.genkan.core

import android.media.AudioDeviceInfo
import com.swordfish.libretrodroid.GLRetroView

/** What the built-in player says on screen when a game can't start, or can't be heard. */
object PlayerMessages {

    /** Why the core stopped, for one of libretrodroid's error codes. */
    fun loadError(code: Int, core: String): String = when (code) {
        GLRetroView.ERROR_LOAD_LIBRARY -> "$core couldn't be started"
        GLRetroView.ERROR_LOAD_GAME -> "$core couldn't load the game file"
        GLRetroView.ERROR_GL_NOT_COMPATIBLE -> "$core needs graphics this device doesn't have"
        else -> "$core stopped with an error"
    }

    /** Shown when a game starts while the media volume can't be heard. */
    const val MEDIA_VOLUME_OFF = "Media volume is off"

    /** Shown when the game can't be heard: its sound stream wouldn't open, or stopped. */
    fun noSound(problem: String): String = "No sound: $problem"

    /** Whether the media volume is off: at zero, or muted. */
    fun volumeOff(volume: Int, muted: Boolean): Boolean = volume == 0 || muted

    /** An output device as the log names it: its type, for telling speaker, Bluetooth and wired apart. */
    fun outputType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth a2dp"
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "bluetooth le"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth sco"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        else -> "type $type"
    }
}
