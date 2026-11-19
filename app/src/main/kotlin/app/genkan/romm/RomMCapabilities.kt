// Adapted from Argosy Launcher (GPL-3.0) @ 3480817: app/src/main/kotlin/com/nendo/argosy/data/remote/romm/RomMCapabilities.kt
// Trimmed to the gates Genkan reads.
package app.genkan.romm

data class RomMCapabilities(
    val serverVersion: String,
    val isSupportedVersion: Boolean,
    val supportsPlaySessionIngest: Boolean,
    val supportsDeviceAuth: Boolean,
) {
    companion object {
        /** RomM 5.x: client tokens, device-code login and play-session ingest all exist there. */
        const val MIN_SUPPORTED_VERSION = "5.0.0"
        const val PLAY_SESSION_MIN_VERSION = "4.9.0"
        const val DEVICE_AUTH_MIN_VERSION = "5.0.0"

        val NONE = RomMCapabilities(
            serverVersion = "",
            isSupportedVersion = false,
            supportsPlaySessionIngest = false,
            supportsDeviceAuth = false,
        )

        fun from(version: String?): RomMCapabilities {
            if (version.isNullOrBlank() || version == "unknown") return NONE
            return RomMCapabilities(
                serverVersion = version,
                isSupportedVersion = compareVersions(version, MIN_SUPPORTED_VERSION) >= 0,
                supportsPlaySessionIngest = compareVersions(version, PLAY_SESSION_MIN_VERSION) >= 0,
                supportsDeviceAuth = compareVersions(version, DEVICE_AUTH_MIN_VERSION) >= 0,
            )
        }

        fun compareVersions(v1: String, v2: String): Int {
            val parts1 = v1.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }
            val parts2 = v2.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(parts1.size, parts2.size)
            for (i in 0 until maxLen) {
                val p1 = parts1.getOrElse(i) { 0 }
                val p2 = parts2.getOrElse(i) { 0 }
                if (p1 != p2) return p1.compareTo(p2)
            }
            return 0
        }
    }
}
