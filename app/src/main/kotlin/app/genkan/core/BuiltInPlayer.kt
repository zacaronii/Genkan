package app.genkan.core

import android.graphics.Bitmap
import android.os.IBinder
import android.os.RemoteException
import android.os.SharedMemory
import android.util.Log
import app.genkan.yomikata.CatalogEntry
import app.genkan.yomikata.LayoutRead
import app.genkan.yomikata.Layouts
import androidx.core.graphics.createBitmap
import app.genkan.memory.MemorySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A game running in the built-in player, as the reader's process sees it. */
data class CoreGame(val session: ICoreSession, val romId: Long, val core: CoreDef)

/**
 * The reader's link to the built-in player, which runs in its own process (cores aren't made to
 * be loaded twice in one, and a crashing core must not take the launcher with it). Frames and
 * memory are read through the player's session; its sound arrives at [CoreHostService].
 */
@Singleton
class BuiltInPlayer @Inject constructor() {

    private val _game = MutableStateFlow<CoreGame?>(null)
    val game: StateFlow<CoreGame?> = _game.asStateFlow()

    fun attach(session: ICoreSession, romId: Long, core: CoreDef) {
        val game = CoreGame(session, romId, core)
        try {
            session.asBinder().linkToDeath(object : IBinder.DeathRecipient {
                override fun binderDied() {
                    Log.i(TAG, "The player's process ended")
                    _game.compareAndSet(game, null)
                }
            }, 0)
        } catch (e: RemoteException) {
            return
        }
        Log.i(TAG, "Playing rom $romId in ${core.displayName}")
        _game.value = game
    }

    private val _layoutReads = kotlinx.coroutines.flow.MutableSharedFlow<LayoutRead>(extraBufferCapacity = 16)
    /** What the watched layout reads, each time it changes (see [watchLayout]). */
    val layoutReads: kotlinx.coroutines.flow.SharedFlow<LayoutRead> = _layoutReads

    /** The player read the watched layout: something changed. */
    fun layoutRead(json: String) {
        runCatching { Layouts.fromJson(org.json.JSONObject(json)) }.getOrNull()?.let { _layoutReads.tryEmit(it) }
    }

    /**
     * Asks the player to read [layout] between frames (null: stop), with [entry]'s table, unit,
     * controls and cleanup; whether the player took it.
     */
    fun watchLayout(layout: org.json.JSONObject?, entry: CatalogEntry?, tableText: String = ""): Boolean = try {
        val entryJson = org.json.JSONObject()
            .put("unit", entry?.unit ?: 2)
            .put("controls", org.json.JSONObject((entry?.controls ?: emptyMap()) as Map<*, *>))
            .put("cleanup", entry?.cleanupJson ?: org.json.JSONArray())
        _game.value?.session?.watchLayout(layout?.toString().orEmpty(), tableText, entryJson.toString()) == true
    } catch (e: RemoteException) {
        false
    }

    /** Asks the player to save and close its game. */
    fun quit() {
        try {
            _game.value?.session?.quit()
        } catch (e: RemoteException) {
            _game.value = null
        }
    }

    /** The frame the player shows now ([native]: at the core's own size), or null when no game runs in it. */
    fun frame(native: Boolean = false): Bitmap? {
        val session = _game.value?.session ?: return null
        val bundle = try {
            if (native) session.nativeFrame() else session.frame()
        } catch (e: RemoteException) {
            null
        } ?: return null
        val width = bundle.getInt(FRAME_WIDTH)
        val height = bundle.getInt(FRAME_HEIGHT)
        val pixels = bundle.getParcelable(FRAME_PIXELS, SharedMemory::class.java) ?: return null
        return pixels.use { memory ->
            val buffer = memory.mapReadOnly()
            try {
                createBitmap(width, height).apply { copyPixelsFromBuffer(buffer) }
            } finally {
                SharedMemory.unmap(buffer)
            }
        }
    }

    /** The running game's memory, or null when no game runs in the player. */
    fun memory(): MemorySource? {
        val game = _game.value ?: return null
        val raw = try {
            game.session.regions()
        } catch (e: RemoteException) {
            return null
        }
        val base = game.core.ramBase
        val regions = raw.toList().chunked(3).map { (start, length, mirror) -> (base + start until base + start + length) to (mirror != 0L) }
        return CoreMemory(game.session, regions.filter { !it.second }.map { it.first }, regions.map { it.first }, game.core.pointerMask, base)
    }

    /** The running game's savestate, gzipped into [file] (in the snapshot folder); its size before gzip, or null. */
    fun saveState(file: java.io.File): Long? {
        val session = _game.value?.session ?: return null
        return try {
            session.saveStateTo(file.absolutePath).takeIf { it > 0 }
        } catch (e: RemoteException) {
            null
        }
    }

    /**
     * The running game's video RAM (a PS1's 1024×512 16-bit frame buffer and texture pages,
     * where its font and the drawn text box are), or null when the core gives none.
     */
    fun videoRam(): ByteArray? {
        val session = _game.value?.session ?: return null
        return try {
            val size = session.videoSize().toInt()
            if (size <= 0) return null
            val out = ByteArray(size)
            var at = 0
            while (at < size) {
                val n = minOf(VIDEO_CHUNK, size - at)
                (session.readVideo(at.toLong(), n) ?: return null).copyInto(out, at)
                at += n
            }
            out
        } catch (e: RemoteException) {
            null
        }
    }

    companion object {
        private const val VIDEO_CHUNK = 64 * 1024
        private const val TAG = "BuiltInPlayer"
        const val FRAME_WIDTH = "width"
        const val FRAME_HEIGHT = "height"
        const val FRAME_PIXELS = "pixels"
    }
}

/** A game's memory in the built-in player, read through its session. */
private class CoreMemory(
    private val session: ICoreSession,
    override val regions: List<LongRange>,
    private val all: List<LongRange>,
    private val pointerMask: Long,
    /** Added to the core's own addresses to give the game's. */
    private val base: Long,
) : MemorySource {
    override val maxRead: Int = MAX_READ

    override fun target(value: Long): Long? = (value and pointerMask).takeIf { v -> all.any { v in it } }

    override fun read(address: Long, size: Int): ByteArray? = try {
        session.read(address - base, size)
    } catch (e: RemoteException) {
        null
    }

    override fun close() = Unit

    private companion object {
        /** Well under the binder's 1 MB a call. */
        const val MAX_READ = 64 * 1024
    }
}
