// Adapted from Argosy Launcher (GPL-3.0) @ 3480817:
// app/src/main/kotlin/com/nendo/argosy/data/launcher/FrontendSyncMarkers.kt (marker rules),
// GameNativeSyncFolder.kt and GameNativeLauncher.kt (the LAUNCH_GAME intent).
package app.genkan.launch

import android.content.ComponentName
import android.content.Intent
import java.io.File

/** A PC game GameNative has installed, read from its Frontend Sync export. */
data class PcGame(val appId: Int, val title: String) {
    /** Steam's portrait library art, matching the 3:4 cards. */
    val coverUrl: String get() = "https://cdn.cloudflare.steamstatic.com/steam/apps/$appId/library_600x900.jpg"
}

object GameNative {
    const val PACKAGE = "app.gamenative"

    /**
     * GameNative's Steam export: `Games/Steam/<Title>.steam`, each file holding the numeric app
     * id. GOG, Epic and Amazon exports exist too but need a game_source Genkan hasn't verified.
     */
    fun steamFolder(externalRoot: File) = File(externalRoot, "Games/Steam")

    private const val MAX_MARKER_BYTES = 128L
    private val MARKER_ID = Regex("[1-9][0-9]{0,19}")

    /** The games in [dir]; empty when the folder is missing or unreadable. */
    fun readMarkers(dir: File, extension: String = ".steam"): List<PcGame> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(extension, ignoreCase = true) && it.length() <= MAX_MARKER_BYTES }
            .mapNotNull { file ->
                val id = runCatching { file.readText() }.getOrNull()?.let(::parseId) ?: return@mapNotNull null
                PcGame(id, file.name.dropLast(extension.length))
            }
            .sortedBy { it.title.lowercase() }

    /** A marker holds one positive decimal id; ids past the 32-bit range are not Steam ids. */
    fun parseId(content: String): Int? = content.trim().takeIf { MARKER_ID.matches(it) }?.toIntOrNull()

    fun launchIntent(game: PcGame): Intent = Intent("app.gamenative.LAUNCH_GAME")
        .setComponent(ComponentName(PACKAGE, "$PACKAGE.MainActivity"))
        .putExtra("app_id", game.appId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
