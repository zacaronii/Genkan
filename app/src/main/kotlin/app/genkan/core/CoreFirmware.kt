package app.genkan.core

import android.content.Context
import android.util.Log
import app.genkan.romm.RomMSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies the BIOS files a core boots with from the platform's firmware in RomM into the core's
 * system folder, once. Without them (offline, or a sign-in from before Genkan asked to read
 * firmware) the core starts as it would have anyway.
 */
@Singleton
class CoreFirmware @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: RomMSession,
) {
    /** The folder the player gives its cores as their system directory. */
    val systemDir: File get() = File(context.filesDir, "system")

    suspend fun fetch(core: CoreDef, platformId: Long) = withContext(Dispatchers.IO) {
        if (core.firmware.isEmpty()) return@withContext
        val api = session.api() ?: return@withContext
        try {
            val response = api.getFirmware(platformId)
            if (!response.isSuccessful) {
                Log.i(TAG, "RomM didn't list firmware (${response.code()})")
                return@withContext
            }
            val files = response.body().orEmpty().filter { !it.missingFromFs && it.fileName in core.firmware }
            for (firmware in files) {
                val target = File(systemDir.apply { mkdirs() }, core.firmware.getValue(firmware.fileName))
                if (target.length() == firmware.fileSizeBytes) continue
                val download = api.downloadFirmware(firmware.id, firmware.fileName)
                val body = download.body()
                if (!download.isSuccessful || body == null) {
                    Log.i(TAG, "RomM refused ${firmware.fileName} (${download.code()})")
                    continue
                }
                val part = File(target.path + ".part")
                body.use { b -> part.outputStream().use { b.byteStream().copyTo(it) } }
                if (part.length() == firmware.fileSizeBytes && part.renameTo(target)) {
                    Log.i(TAG, "Fetched ${firmware.fileName} as ${target.name} for ${core.displayName}")
                } else {
                    part.delete()
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "No firmware fetched: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "CoreFirmware"
    }
}
