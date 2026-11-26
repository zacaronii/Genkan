package app.genkan.core

import android.graphics.Bitmap
import android.os.IBinder
import android.os.RemoteException
import android.os.SharedMemory
import android.util.Log
import androidx.core.graphics.createBitmap
import app.genkan.memory.MemorySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** A game running in the built-in player, as the reader's process sees it. */
/** [look]: what the player draws the game with now; [kept]: the look the game would start with next time. */
data class CoreGame(
    val session: ICoreSession,
    val romId: Long,
    val core: CoreDef,
    val look: ScreenLook = ScreenLook.DEFAULT,
    val kept: ScreenLook = look,
)

/**
 * The reader's link to the built-in player, which runs in its own process (cores aren't made to
 * be loaded twice in one, and a crashing core must not take the launcher with it). Frames and
 * memory are read through the player's session; its sound arrives at [CoreHostService].
 */
@Singleton
class BuiltInPlayer @Inject constructor() {

    private val _game = MutableStateFlow<CoreGame?>(null)
    val game: StateFlow<CoreGame?> = _game.asStateFlow()

    fun attach(session: ICoreSession, romId: Long, core: CoreDef, look: ScreenLook = ScreenLook.DEFAULT) {
        val game = CoreGame(session, romId, core, look)
        try {
            session.asBinder().linkToDeath(object : IBinder.DeathRecipient {
                override fun binderDied() {
                    Log.i(TAG, "The player's process ended")
                    _game.update { if (it?.session == session) null else it }
                }
            }, 0)
        } catch (e: RemoteException) {
            return
        }
        Log.i(TAG, "Playing rom $romId in ${core.displayName}")
        _game.value = game
    }

    private val _scriptEvents = kotlinx.coroutines.flow.MutableSharedFlow<List<Pair<Int, String>>>(extraBufferCapacity = 64)
    /** Each frame's events from the running Yomikata script (kind and JSON), in order. */
    val scriptEvents: kotlinx.coroutines.flow.SharedFlow<List<Pair<Int, String>>> = _scriptEvents

    fun scriptEvents(events: List<Pair<Int, String>>) {
        _scriptEvents.tryEmit(events)
    }

    private val _scriptStateLoads = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** A savestate was loaded in the player, and its script told. */
    val scriptStateLoads: kotlinx.coroutines.flow.SharedFlow<Unit> = _scriptStateLoads

    fun scriptStateLoaded() {
        _scriptStateLoads.tryEmit(Unit)
    }

    /** Where the game's picture sits on the player's view (left, top, right, bottom fractions), or null. */
    fun pictureBounds(): FloatArray? = try {
        _game.value?.session?.pictureBounds()
    } catch (e: RemoteException) {
        null
    }

    /** Moves the running game to ladder step [step]; false when that waits for its next start, or no game runs. */
    fun setTuningStep(step: String): Boolean = try {
        _game.value?.session?.setTuningStep(step) ?: false
    } catch (e: RemoteException) {
        false
    }

    /** Plugs Wii controller [choice] (WiiController) into the running game; false when no Wii game runs. */
    fun setWiiController(choice: String): Boolean = try {
        _game.value?.session?.setWiiController(choice) ?: false
    } catch (e: RemoteException) {
        false
    }

    /** The script feature level the player's runtime gives; 0 without a game. */
    fun scriptLevel(): Int = try {
        _game.value?.session?.scriptLevel() ?: 0
    } catch (e: RemoteException) {
        0
    }

    private val _overlayTaps = kotlinx.coroutines.flow.MutableSharedFlow<app.genkan.ocr.ScreenText.Hit>(extraBufferCapacity = 4)
    /** Taps on the overlay over the game: a line and the character in it, to look up. */
    val overlayTaps: kotlinx.coroutines.flow.SharedFlow<app.genkan.ocr.ScreenText.Hit> = _overlayTaps

    fun overlayTapped(text: String, offset: Int) {
        _overlayTaps.tryEmit(app.genkan.ocr.ScreenText.Hit(text, offset))
    }

    /** Draws the running game with [look] for the rest of this play; nothing is saved. */
    fun setLook(look: ScreenLook) {
        val game = _game.value ?: return
        try {
            game.session.setLook(look.name)
            _game.compareAndSet(game, game.copy(look = look))
        } catch (e: RemoteException) {
            Log.w(TAG, "Player unreachable: $e")
        }
    }

    /** The look on screen is now the game's own ([ScreenLooks.setGame] saved it). */
    fun lookKept() {
        _game.update { it?.copy(kept = it.look) }
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
        return bitmapOf(bundle)
    }

    /**
     * One moment of the game: the frame drawn and [range] of its memory (the game's own
     * addresses) as they were right after it, nothing run in between. The memory passes through
     * [file] (in the snapshot folder), which is gone after. Null when the player can't.
     */
    fun moment(range: LongRange, file: java.io.File): app.genkan.memory.RamSnapshots.Moment? {
        val game = _game.value ?: return null
        val length = (range.last - range.first + 1).toInt()
        // The player puts the video RAM of the same frame beside it, when the core gives one.
        val video = java.io.File(file.path + VRAM_SUFFIX)
        return try {
            if (!game.session.captureTo(file.absolutePath, range.first - game.core.ramBase, length)) return null
            val ram = file.readBytes()
            val vram = video.takeIf { it.isFile }?.readBytes()
            val frame = game.session.keptFrame()?.let(::bitmapOf)
            app.genkan.memory.RamSnapshots.Moment(frame, ram, vram)
        } catch (e: RemoteException) {
            null
        } catch (e: java.io.IOException) {
            null
        } finally {
            file.delete()
            video.delete()
        }
    }

    private fun bitmapOf(bundle: android.os.Bundle): Bitmap? {
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
    /** The playing cartridge's catalog id and header CRC-32 ([CartridgeId]); null for a disc, or no game. */
    fun cartridgeId(): CartridgeId? {
        val game = _game.value ?: return null
        val raw = try {
            game.session.cartridgeId()
        } catch (e: RemoteException) {
            null
        } ?: return null
        val (id, crc) = raw.split(' ').takeIf { it.size == 2 } ?: return null
        return CartridgeId(id, crc)
    }

    fun memory(): MemorySource? {
        val game = _game.value ?: return null
        val raw = try {
            game.session.regions()
        } catch (e: RemoteException) {
            return null
        }
        val base = game.core.ramBase
        val regions = raw.toList().chunked(3).map { (start, length, mirror) -> (base + start until base + start + length) to (mirror != 0L) }
        return CoreMemory(game.session, regions.filter { !it.second }.map { it.first }, regions.map { it.first }, game.core.pointerMask, base, game.core.bigEndian)
    }

    /**
     * Runs a Yomikata script for the running game from now on: [source], its [data] files by name,
     * and the game's id and build as the catalog writes them. Null when it loaded, else why not.
     */
    fun loadScript(name: String, source: ByteArray, data: Map<String, ByteArray>, gameId: String, buildId: String?): String? {
        val session = _game.value?.session ?: return "no game in the built-in player"
        val files = android.os.Bundle().apply { data.forEach { (key, bytes) -> putByteArray(key, bytes) } }
        return try {
            session.loadScript(name, source, files, gameId, buildId)
        } catch (e: RemoteException) {
            "the player didn't answer"
        }
    }

    fun unloadScript() {
        try {
            _game.value?.session?.unloadScript()
        } catch (e: RemoteException) {
            // The player is gone, and its script with it.
        }
    }

    /** The last [millis] of the running game's picture as a GIF in [file]; whether it made one. */
    fun recentGif(file: java.io.File, millis: Int): Boolean {
        val session = _game.value?.session ?: return false
        return try {
            session.recentGif(file.absolutePath, millis)
        } catch (e: RemoteException) {
            false
        }
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
    /** Whether the core gives out its video RAM (a script's `vram` region). */
    fun hasVideoRam(): Boolean = try {
        (_game.value?.session?.videoSize() ?: 0L) > 0L
    } catch (e: RemoteException) {
        false
    }

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
        /** Beside a capture's RAM file: the video RAM of the same frame ([moment]). */
        const val VRAM_SUFFIX = ".vram"
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
    override val bigEndian: Boolean,
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
