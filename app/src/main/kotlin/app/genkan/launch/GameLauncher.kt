package app.genkan.launch

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import android.content.Intent
import app.genkan.core.BuiltInPlayer
import app.genkan.core.CoreActivity
import app.genkan.core.CoreCatalog
import app.genkan.core.GameFile
import app.genkan.core.CoreDef
import app.genkan.core.CoreFirmware
import app.genkan.core.PlayerSettings
import app.genkan.core.ScreenLooks
import app.genkan.download.DownloadEntity
import app.genkan.library.GameEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class LaunchResult {
    /** Started in [emulator]; [note] says why it isn't the one the player would have used, if so. */
    data class Launched(val emulator: EmulatorDef, val note: String? = null) : LaunchResult()
    /** None of the emulators that play this platform is installed. */
    data class NoEmulator(val suggestions: List<EmulatorDef>) : LaunchResult()
    data class Failed(val message: String) : LaunchResult()
}

/**
 * A game got ready to start: its files found, the emulator chosen, firmware fetched. Getting
 * ready and starting are apart so the library can do the first while the insert animation plays
 * and the second when it ends (see [app.genkan.library.InsertTimeline]).
 */
sealed class LaunchPlan {
    /** Ready; [start] starts it. [builtIn] is whether it runs in Genkan's own player. */
    class Ready(val builtIn: Boolean, val start: suspend () -> LaunchResult) : LaunchPlan()

    /** It can't start ([result] says why: no emulator, missing files). */
    data class Unavailable(val result: LaunchResult) : LaunchPlan()
}

/** Starts a downloaded game in the first installed emulator for its platform, on the top screen. */
@Singleton
class GameLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerSettings: PlayerSettings,
    private val player: BuiltInPlayer,
    private val firmware: CoreFirmware,
    private val screenLooks: ScreenLooks,
    private val tuning: app.genkan.core.GameTuning,
    private val wiiController: app.genkan.core.GameWiiController,
) {
    private val builder = LaunchCommandBuilder(
        externalRoot = Environment.getExternalStorageDirectory().absolutePath,
        contentUri = { file -> FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString() },
        mimeTypeOf = { file ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        },
    )

    private val builtInOn = playerSettings.enabled.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, false)

    fun installedEmulatorFor(platformSlug: String): Pair<EmulatorDef, String?>? {
        CoreCatalog.forPlatform(platformSlug)?.takeIf { builtInOn.value }?.let { return builtInEmulator(it) to null }
        return installedAppFor(platformSlug)
    }

    /** The first installed emulator app for [platformSlug], never the built-in player. */
    private fun installedAppFor(platformSlug: String): Pair<EmulatorDef, String?>? =
        EmulatorCatalog.candidatesFor(platformSlug).firstNotNullOfOrNull { emulator ->
            installedVersion(emulator.packageName)?.let { emulator to it.ifEmpty { null } }
        }

    /** The built-in player, when it is switched on and has a core for [platformSlug]. */
    private suspend fun builtInFor(platformSlug: String): CoreDef? =
        CoreCatalog.forPlatform(platformSlug)?.takeIf { playerSettings.isEnabled() }

    /**
     * Gets [game] ready and starts it straight away, with no animation. The debug receiver uses
     * it; the library goes through [prepare] so the insert animation can play in between.
     */
    suspend fun launch(game: GameEntity, download: DownloadEntity): LaunchResult = when (val plan = prepare(game, download)) {
        is LaunchPlan.Ready -> plan.start()
        is LaunchPlan.Unavailable -> plan.result
    }

    /**
     * Gets [game] ready to start without starting it: finds its files, picks the emulator, fetches
     * firmware, and closes a different game still open in the built-in player.
     */
    suspend fun prepare(game: GameEntity, download: DownloadEntity): LaunchPlan = withContext(Dispatchers.IO) {
        val installed = File(download.path)
        val rom = if (installed.isDirectory) LaunchCommandBuilder.bootFileIn(installed) else installed
        if (rom == null || !rom.exists()) return@withContext LaunchPlan.Unavailable(LaunchResult.Failed("The game's files are missing; download it again"))
        val builtIn = builtInFor(game.platformSlug)
        if (builtIn != null) BuiltInBoot.fileFor(builtIn, rom)?.let { return@withContext prepareBuiltIn(game, it, builtIn) }
        // A file the built-in core can't play (nor a zip holding one) goes to an emulator app below, saying so.
        val note = builtIn?.let { "${it.displayName} can't play this file" }

        val (emulator, version) = installedAppFor(game.platformSlug)
            ?: return@withContext LaunchPlan.Unavailable(LaunchResult.NoEmulator(EmulatorCatalog.candidatesFor(game.platformSlug)))

        withTimeoutOrNull(FIRMWARE_TIMEOUT_MS) { firmware.fetch(emulator, game.platformId) }
        val boot = if (!emulator.opensPlaylists && rom.extension.equals("m3u", true)) LaunchCommandBuilder.firstListed(rom) ?: rom else rom
        val command = builder.build(emulator, boot, game.platformSlug, version)
            ?: return@withContext LaunchPlan.Unavailable(LaunchResult.Failed("${emulator.displayName} has no core set for ${game.platformSlug}"))
        LaunchPlan.Ready(builtIn = false) {
            withContext(Dispatchers.IO) {
                Log.i(TAG, "Launching ${game.name} in ${emulator.displayName} (${command.method}): ${boot.name}")
                try {
                    command.grantReads(context)
                    if (command.method == LaunchMethod.INTENT || !launchViaShell(command)) {
                        withContext(Dispatchers.Main) { context.startActivity(command.toIntent(), topScreenOptions()) }
                    }
                    LaunchResult.Launched(emulator, note)
                } catch (e: Exception) {
                    Log.w(TAG, "Launch failed", e)
                    LaunchResult.Failed(e.message ?: "Launch failed")
                }
            }
        }
    }

    /**
     * Gets [rom] ready for the built-in player. A different game still open there is saved and
     * closed first: its process can hold only one core.
     */
    private suspend fun prepareBuiltIn(game: GameEntity, rom: File, core: CoreDef): LaunchPlan {
        val running = player.game.value
        if (running != null && running.romId != game.id) {
            Log.i(TAG, "Closing rom ${running.romId} in the built-in player first")
            player.quit()
            withTimeoutOrNull(QUIT_TIMEOUT_MS) { player.game.first { it == null } }
        }
        withTimeoutOrNull(FIRMWARE_TIMEOUT_MS) { firmware.fetch(core, game.platformId) }
        val intent = Intent(context, CoreActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CoreActivity.EXTRA_ROM, rom.absolutePath)
            .putExtra(CoreActivity.EXTRA_ROM_ID, game.id)
            .putExtra(CoreActivity.EXTRA_PLATFORM, game.platformSlug)
            .putExtra(CoreActivity.EXTRA_CORE, core.id)
            .putExtra(CoreActivity.EXTRA_LOOK, screenLooks.forLaunch(game.id, game.platformSlug).name)
            .putExtra(CoreActivity.EXTRA_LOW_LATENCY, playerSettings.lowLatency(game.platformSlug))
            .putExtra(CoreActivity.EXTRA_A_ON_RIGHT, playerSettings.aOnRight(game.platformSlug))
            .putExtra(CoreActivity.EXTRA_LEARN, playerSettings.learns())
        tuning.resolve(game.id, core.tuning)?.let {
            Log.i(TAG, "Tuning step ${it.step.id} (${it.source.name.lowercase()})")
            intent.putExtra(CoreActivity.EXTRA_TUNING_STEP, it.step.id)
        }
        if (app.genkan.core.WiiController.OPTION in core.variables) {
            wiiController.resolve(game.id)?.let {
                Log.i(TAG, "Wii controller $it")
                intent.putExtra(CoreActivity.EXTRA_WII_CONTROLLER, it)
            }
        }
        return LaunchPlan.Ready(builtIn = true) {
            Log.i(TAG, "Launching ${game.name} in the built-in player (${core.displayName}): ${rom.name}")
            try {
                withContext(Dispatchers.Main) { context.startActivity(intent, topScreenOptions()) }
                LaunchResult.Launched(builtInEmulator(core))
            } catch (e: Exception) {
                Log.w(TAG, "Launch failed", e)
                LaunchResult.Failed(e.message ?: "Launch failed")
            }
        }
    }

    /** The built-in player as an emulator, for what the library shows and play sessions record. */
    private fun builtInEmulator(core: CoreDef) = EmulatorDef(
        id = "genkan_${core.id}",
        packageName = context.packageName,
        displayName = "Genkan (${core.displayName})",
        platforms = core.platforms,
        recipe = Recipe.Custom(activity = CoreActivity::class.java.name),
    )

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
        const val QUIT_TIMEOUT_MS = 5_000L
        /** A BIOS is half a megabyte: a slow or absent server mustn't hold the game up longer. */
        const val FIRMWARE_TIMEOUT_MS = 10_000L
    }
}
