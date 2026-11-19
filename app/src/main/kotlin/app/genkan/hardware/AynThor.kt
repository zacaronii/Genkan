// Adapted from Argosy Launcher (GPL-3.0) @ 3480817: app/src/main/kotlin/com/nendo/argosy/util/AynThor.kt
package app.genkan.hardware

import android.os.Build

object AynThor {
    fun matches(model: String? = Build.MODEL): Boolean = model?.contains("thor", ignoreCase = true) == true
}
