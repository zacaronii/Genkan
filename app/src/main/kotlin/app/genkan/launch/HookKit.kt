package app.genkan.launch

import android.content.Context
import android.os.Environment
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts the PC hook kit (tools/hookbridge, packed as assets) in Download/Genkan/hook, which
 * GameNative's containers see as D:\Genkan\hook. The game's own folder is private to
 * GameNative, so one step stays manual, once per game: turn on "Enable mod loaders" in its
 * container settings, then run setup.bat from its "Open container" desktop, which copies the
 * version.dll proxy into the game folder (A:). The proxy finds the kit on D: by itself.
 */
@Singleton
class HookKit @Inject constructor(@ApplicationContext private val context: Context) {

    val dir: File get() = File(Environment.getExternalStorageDirectory(), "Download/Genkan/hook")

    /** Copies the kit when it's missing or this build carries another version; true when it's in place. */
    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        val assets = context.assets
        val files = runCatching { assets.list(ASSET_DIR) }.getOrNull().orEmpty()
        if (VERSION !in files) return@withContext false // built without the kit
        val wanted = assets.open("$ASSET_DIR/$VERSION").use { it.readBytes().decodeToString().trim() }
        val stamp = File(dir, VERSION)
        if (stamp.isFile && stamp.readText().trim() == wanted) return@withContext true
        try {
            dir.mkdirs()
            for (name in files) {
                if (name == VERSION) continue
                assets.open("$ASSET_DIR/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
            }
            stamp.writeText(wanted)
            Log.i(TAG, "Hook kit $wanted installed in $dir")
            true
        } catch (e: IOException) {
            Log.i(TAG, "No hook kit installed: ${e.message}")
            false
        }
    }

    private companion object {
        const val TAG = "HookKit"
        const val ASSET_DIR = "hookbridge"
        const val VERSION = "kit.version"
    }
}
