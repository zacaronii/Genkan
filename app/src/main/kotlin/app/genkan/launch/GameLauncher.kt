package app.genkan.launch

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.genkan.download.DownloadEntity
import app.genkan.library.GameEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class LaunchResult {
    data class Launched(val emulator: EmulatorDef) : LaunchResult()
    /** None of the emulators that play this platform is installed. */
    data class NoEmulator(val suggestions: List<EmulatorDef>) : LaunchResult()
    data class Failed(val message: String) : LaunchResult()
}

/** Starts a downloaded game in the first installed emulator for its platform, on the top screen. */
@Singleton
class GameLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val builder = LaunchCommandBuilder(
        externalRoot = Environment.getExternalStorageDirectory().absolutePath,
        contentUri = { file -> FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString() },
        mimeTypeOf = { file ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        },
    )

    fun installedEmulatorFor(platformSlug: String): Pair<EmulatorDef, String?>? =
        EmulatorCatalog.candidatesFor(platformSlug).firstNotNullOfOrNull { emulator ->
            installedVersion(emulator.packageName)?.let { emulator to it.ifEmpty { null } }
        }

    suspend fun launch(game: GameEntity, download: DownloadEntity): LaunchResult = withContext(Dispatchers.IO) {
        val (emulator, version) = installedEmulatorFor(game.platformSlug)
            ?: return@withContext LaunchResult.NoEmulator(EmulatorCatalog.candidatesFor(game.platformSlug))
        val installed = File(download.path)
        val rom = if (installed.isDirectory) LaunchCommandBuilder.bootFileIn(installed) else installed
        if (rom == null || !rom.exists()) return@withContext LaunchResult.Failed("The game's files are missing; download it again")

        val command = builder.build(emulator, rom, game.platformSlug, version)
            ?: return@withContext LaunchResult.Failed("${emulator.displayName} has no core set for ${game.platformSlug}")
        Log.i(TAG, "Launching ${game.name} in ${emulator.displayName} (${command.method}): ${rom.name}")
        try {
            command.grantReads(context)
            if (command.method == LaunchMethod.INTENT || !launchViaShell(command)) {
                withContext(Dispatchers.Main) { context.startActivity(command.toIntent(), topScreenOptions()) }
            }
            LaunchResult.Launched(emulator)
        } catch (e: Exception) {
            Log.w(TAG, "Launch failed", e)
            LaunchResult.Failed(e.message ?: "Launch failed")
        }
    }

    /**
     * Runs `am start` for recipes that prefer it. Some builds (the Thor's among them) refuse `am`
     * from an app process with exit 255 and no output; false tells the caller to fall back to a
     * plain intent, as Argosy does.
     */
    private fun launchViaShell(command: LaunchCommand): Boolean = try {
        val process = ProcessBuilder(*command.toShellArgv()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exit = process.waitFor()
        (exit == 0 && !output.contains("Error", ignoreCase = true)).also { ok ->
            if (!ok) Log.i(TAG, "am start refused (exit $exit: $output), launching via intent")
        }
    } catch (e: Exception) {
        Log.i(TAG, "am start unavailable (${e.message}), launching via intent")
        false
    }

    private fun installedVersion(packageName: String): String? = try {
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        const val TAG = "GameLauncher"
    }
}
