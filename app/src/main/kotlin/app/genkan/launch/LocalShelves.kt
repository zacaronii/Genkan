package app.genkan.launch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import app.genkan.library.GameEntity
import app.genkan.library.PlatformEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shelves that come from the device rather than RomM: GameNative's PC games and the installed
 * Android apps. They are shown as extra platforms in the library with negative ids.
 */
@Singleton
class LocalShelves @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Shelves(val platforms: List<PlatformEntity>, val games: Map<Long, List<GameEntity>>)

    suspend fun load(): Shelves = withContext(Dispatchers.IO) {
        val pc = if (isInstalled(GameNative.PACKAGE)) {
            GameNative.readMarkers(GameNative.steamFolder(Environment.getExternalStorageDirectory())).map { game ->
                entry(id = -game.appId.toLong() - APP_ID_OFFSET, platformId = PC_ID, slug = PC_SLUG, name = game.title, cover = game.coverUrl, fsName = game.appId.toString())
            }
        } else emptyList()
        val apps = launchableApps().map { (label, pkg) ->
            entry(id = -(pkg.hashCode().toLong() and 0x7fffffff) - 1, platformId = APPS_ID, slug = APPS_SLUG, name = label, cover = null, fsName = pkg)
        }
        val platforms = listOfNotNull(
            PlatformEntity(PC_ID, PC_SLUG, null, "PC", pc.size, null).takeIf { pc.isNotEmpty() },
            PlatformEntity(APPS_ID, APPS_SLUG, null, "Apps", apps.size, null).takeIf { apps.isNotEmpty() },
        )
        Shelves(platforms, mapOf(PC_ID to pc, APPS_ID to apps))
    }

    /** The intent that opens a local shelf entry, or null for a RomM game. */
    fun launchIntentFor(game: GameEntity): Intent? = when (game.platformSlug) {
        PC_SLUG -> game.fsName?.toIntOrNull()?.let { GameNative.launchIntent(PcGame(it, game.name)) }
        APPS_SLUG -> game.fsName?.let { context.packageManager.getLaunchIntentForPackage(it) }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        else -> null
    }

    /** Opens a PC game or app on the top screen. False when its launcher is gone. */
    fun launch(game: GameEntity): Boolean {
        val intent = launchIntentFor(game) ?: return false
        return runCatching { context.startActivity(intent, topScreenOptions()) }.isSuccess
    }

    fun appIcon(game: GameEntity): android.graphics.drawable.Drawable? =
        if (game.platformSlug == APPS_SLUG) game.fsName?.let { runCatching { context.packageManager.getApplicationIcon(it) }.getOrNull() } else null

    private fun launchableApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { pkg -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() to pkg }
            .sortedBy { it.first.lowercase() }
    }

    private fun isInstalled(pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun entry(id: Long, platformId: Long, slug: String, name: String, cover: String?, fsName: String) =
        GameEntity(id, platformId, slug, name, name.lowercase(), fsName, 0, cover, false)

    companion object {
        const val PC_ID = -1L
        const val APPS_ID = -2L
        const val PC_SLUG = "genkan-pc"
        const val APPS_SLUG = "genkan-apps"
        private const val APP_ID_OFFSET = 1_000_000_000L

        fun isLocal(game: GameEntity) = game.platformId < 0
    }
}
