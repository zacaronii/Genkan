// Adapted from Argosy Launcher (GPL-3.0) @ 3480817: app/src/main/kotlin/com/nendo/argosy/util/DisplayAffinityHelper.kt
package app.genkan.display

import android.view.Display

/** What [DisplayFilter] needs to know about a display, so the rule is testable off-device. */
data class DisplayInfo(
    val displayId: Int,
    /** Hidden Display.getType(): 1 built-in, 2 external, 3 wifi, 4 overlay, 5 virtual; null if unreadable. */
    val type: Int?,
    val flags: Int,
    val ownerPackage: String?,
    val isOn: Boolean,
)

/**
 * Picks the real screens out of DisplayManager's list (T9). The Thor's bottom panel carries
 * FLAG_PRESENTATION, so that flag must not exclude anything; emulators such as Azahar add
 * virtual displays mid-session, and Genkan's own capture display must never count.
 */
object DisplayFilter {

    const val TYPE_BUILT_IN = 1
    const val TYPE_EXTERNAL = 2
    const val TYPE_VIRTUAL = 5

    /** Owner of the Android Emulator's extra displays (`adb emu multidisplay add`). */
    const val EMULATOR_DISPLAY_OWNER = "com.android.emulator.multidisplay"

    fun screens(
        displays: List<DisplayInfo>,
        ownPackage: String,
        allowEmulatorDisplays: Boolean = false,
    ): List<DisplayInfo> = displays.filter { isScreen(it, ownPackage, allowEmulatorDisplays) }

    fun isScreen(display: DisplayInfo, ownPackage: String, allowEmulatorDisplays: Boolean = false): Boolean {
        if (display.ownerPackage == ownPackage) return false
        if (display.flags and Display.FLAG_PRIVATE != 0) return false
        return when (display.type) {
            TYPE_BUILT_IN, TYPE_EXTERNAL -> true
            // The Android Emulator's extra displays are virtual displays owned by its
            // multidisplay service; accepted only there, so the dual-screen path can be tested.
            TYPE_VIRTUAL -> allowEmulatorDisplays && display.ownerPackage == EMULATOR_DISPLAY_OWNER
            else -> false
        }
    }

    /** The screen the companion belongs on: the first real screen that is not the default one. */
    fun secondaryDisplayId(screens: List<DisplayInfo>): Int? =
        screens.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isOn }?.displayId
}
