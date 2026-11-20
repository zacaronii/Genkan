package app.genkan.core

import android.content.Context
import android.os.Environment
import android.util.Log
import app.genkan.launch.EmulatorDef
import app.genkan.privileged.Privileged
import app.genkan.romm.RomMApi
import app.genkan.romm.RomMFirmware
import app.genkan.romm.RomMSession
import app.genkan.saves.SaveSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies the BIOS files a core or an emulator boots with from the platform's firmware in RomM,
 * once: into the core's system folder, or into the emulator's own BIOS folder (another app's
 * Android/data, through Shizuku). Without them (offline, no Shizuku, or a sign-in from before
 * Genkan asked to read firmware) the game starts as it would have anyway.
 */
@Singleton
class CoreFirmware @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: RomMSession,
    private val privileged: Privileged,
) {
    /** The folder the player gives its cores as their system directory. */
    val systemDir: File get() = File(context.filesDir, "system")

    private val storage = Environment.getExternalStorageDirectory().path

    suspend fun fetch(core: CoreDef, platformId: Long) = withContext(Dispatchers.IO) {
        val (api, files) = listed(core.firmware, platformId) ?: return@withContext
        for (firmware in files) {
            val target = File(systemDir.apply { mkdirs() }, core.firmware.getValue(firmware.fileName))
            if (target.length() == firmware.fileSizeBytes) continue
            if (download(api, firmware, target)) Log.i(TAG, "Fetched ${firmware.fileName} as ${target.name} for ${core.displayName}")
        }
    }

    suspend fun fetch(emulator: EmulatorDef, platformId: Long) = withContext(Dispatchers.IO) {
        val folder = emulator.firmwareDir ?: return@withContext
        val (api, files) = listed(emulator.firmware, platformId) ?: return@withContext
        val helper = privileged.files()
        if (helper == null) {
            Log.i(TAG, "No Shizuku: no BIOS copied for ${emulator.displayName}")
            return@withContext
        }
        val dir = "$storage/$folder"
        val there = SaveSnapshot.parse(helper.list(dir))
        val stage = File(storage, "Genkan/.staging/bios")
        try {
            for (firmware in files) {
                val name = emulator.firmware.getValue(firmware.fileName)
                if (there[name]?.size == firmware.fileSizeBytes) continue
                val local = File(stage.apply { mkdirs() }, name)
                if (!download(api, firmware, local)) continue
                if (helper.copy(local.path, "$dir/$name")) {
                    Log.i(TAG, "Copied ${firmware.fileName} as $name for ${emulator.displayName}")
                } else {
                    Log.i(TAG, "Couldn't copy ${firmware.fileName} into $dir")
                }
            }
        } finally {
            stage.deleteRecursively()
        }
    }

    /** RomM's firmware for the platform that [wanted] names; null when there's nothing to fetch. */
    private suspend fun listed(wanted: Map<String, String>, platformId: Long): Pair<RomMApi, List<RomMFirmware>>? {
        if (wanted.isEmpty()) return null
        val api = session.api() ?: return null
        return try {
            val response = api.getFirmware(platformId)
            if (!response.isSuccessful) {
                Log.i(TAG, "RomM didn't list firmware (${response.code()})")
                return null
            }
            api to response.body().orEmpty().filter { !it.missingFromFs && it.fileName in wanted }
        } catch (e: IOException) {
            Log.i(TAG, "No firmware listed: ${e.message}")
            null
        }
    }

    /** Downloads [firmware] to [target]; true when the whole file arrived. */
    private suspend fun download(api: RomMApi, firmware: RomMFirmware, target: File): Boolean = try {
        val download = api.downloadFirmware(firmware.id, firmware.fileName)
        val body = download.body()
        if (!download.isSuccessful || body == null) {
            Log.i(TAG, "RomM refused ${firmware.fileName} (${download.code()})")
            false
        } else {
            val part = File(target.path + ".part")
            body.use { b -> part.outputStream().use { b.byteStream().copyTo(it) } }
            (part.length() == firmware.fileSizeBytes && part.renameTo(target)).also { if (!it) part.delete() }
        }
    } catch (e: IOException) {
        Log.i(TAG, "No firmware fetched: ${e.message}")
        false
    }

    private companion object {
        const val TAG = "CoreFirmware"
    }
}
