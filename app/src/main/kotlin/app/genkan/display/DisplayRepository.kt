package app.genkan.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Size
import android.view.Display
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The one place Genkan enumerates displays and reads their sizes. */
@Singleton
class DisplayRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    /** True on the Android Emulator, where the added secondary display is a virtual one. */
    private val onEmulator: Boolean =
        Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")

    fun screens(): List<DisplayInfo> =
        DisplayFilter.screens(
            displayManager.displays.map { it.toInfo() },
            ownPackage = context.packageName,
            allowEmulatorDisplays = onEmulator,
        )

    fun secondaryDisplayId(): Int? = DisplayFilter.secondaryDisplayId(screens())

    /**
     * Panel size read through a window context for that display (T7). Other routes report the
     * calling context's metrics, which on the Thor turns 1920x1080 into 1240x1080.
     */
    fun sizeOf(displayId: Int): Size? {
        val display = displayManager.getDisplay(displayId) ?: return null
        val windowContext = context.createWindowContext(
            display, WindowManager.LayoutParams.TYPE_APPLICATION, null
        )
        val bounds = windowContext.getSystemService(WindowManager::class.java)
            .maximumWindowMetrics.bounds
        return Size(bounds.width(), bounds.height())
    }

    fun registerListener(listener: DisplayManager.DisplayListener) {
        displayManager.registerDisplayListener(listener, null)
    }

    fun unregisterListener(listener: DisplayManager.DisplayListener) {
        displayManager.unregisterDisplayListener(listener)
    }

    private fun Display.toInfo() = DisplayInfo(
        displayId = displayId,
        type = readType(),
        flags = flags,
        ownerPackage = readOwnerPackage(),
        isOn = state != Display.STATE_OFF,
    )

    private fun Display.readType(): Int? = try {
        Display::class.java.getMethod("getType").invoke(this) as? Int
    } catch (_: Exception) {
        null
    }

    private fun Display.readOwnerPackage(): String? = try {
        Display::class.java.getMethod("getOwnerPackageName").invoke(this) as? String
    } catch (_: Exception) {
        null
    }
}
