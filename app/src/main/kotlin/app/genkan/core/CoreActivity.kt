package app.genkan.core

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.SharedMemory
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.GenkanTap
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The built-in player: one game in a libretro core, on the top screen, in its own process
 * (`:core`). The core can't be unloaded and loaded again in one process, so quitting ends the
 * process. The reader in Genkan's main process reads the game's memory and frames through the
 * session this hands to [CoreHostService], and gets its sound from there too.
 */
class CoreActivity : ComponentActivity() {

    private lateinit var retroView: GLRetroView
    private lateinit var core: CoreDef
    private lateinit var saveFile: File
    private var romId = 0L
    /** What the game is drawn with: the launcher's choice, or the reader's while it plays. */
    private var look = ScreenLook.DEFAULT
    private var host: ICoreHost? = null
    private var lastSaved: ByteArray? = null
    @Volatile private var quitting = false

    /** A Yomikata layout the reader asked for, read between frames. */
    private val layoutWatch by lazy {
        LayoutWatch(core) { json ->
            try {
                host?.layout(json)
            } catch (e: RemoteException) {
                host = null
            }
        }
    }

    private var selectDown = false
    /** Whether a Back closes something on the bottom screen first ([closedOnBottom]). */
    private val backPress = BackPress(::closedOnBottom)
    /** Whether the stick clicks step the bottom screen: on consoles whose own pad has none. */
    private var stickCycles = false
    private var lowerScreen: LowerScreen? = null
    /**
     * A capture waiting for its frame (see captureTo), run on the emulation thread after each
     * frame until it says it's done: the frame just drawn and the memory then are one moment.
     */
    @Volatile private var capture: (() -> Boolean)? = null
    /** The frame the last capture took, beside its memory; what keptFrame() gives. */
    @Volatile private var captured: KeptFrame? = null
    /** The overlay over the game, for a core with one picture (not yet a DS's two screens). */
    private var overlay: OverlayView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rom = intent.getStringExtra(EXTRA_ROM)?.let(::File)
        val platform = intent.getStringExtra(EXTRA_PLATFORM).orEmpty()
        stickCycles = BottomCycle.takesStickClicks(platform)
        val def = CoreCatalog.forPlatform(platform)
        if (rom == null || !rom.isFile || def == null) {
            Log.w(TAG, "Nothing to play: $rom on $platform")
            finish()
            return
        }
        core = def
        romId = intent.getLongExtra(EXTRA_ROM_ID, 0)
        look = ScreenLook.of(intent.getStringExtra(EXTRA_LOOK))
        saveFile = CoreSaves.fileFor(platform, def, rom)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val system = File(filesDir, "system").apply { mkdirs() }
        def.assets?.let { (zip, folder) -> CoreAssets.unpack(this, zip, File(system, folder)) }

        GenkanTap.setSplitScreens(def.splitScreens)
        val data = GLRetroViewData(this).apply {
            // The linker finds a bare name among the APK's own libraries.
            coreFilePath = def.library
            gameFilePath = rom.absolutePath
            systemDirectory = system.absolutePath
            savesDirectory = CoreSaves.directoryFor(def, File(filesDir, "saves/${def.id}")).apply { mkdirs() }.absolutePath
            variables = def.variables.map { (key, value) -> Variable(key, value) }.toTypedArray()
            saveRAMState = saveFile.takeIf { it.isFile }?.readBytes()?.also { lastSaved = it }
            shader = look.shader
            preferLowLatencyAudio = true
            rumbleEventsEnabled = false
        }
        retroView = GLRetroView(this, data).apply {
            isFocusable = true
            // Touch mode (the bottom screen was tapped last) must not leave it without focus.
            isFocusableInTouchMode = true
            setOnKeyListener { _, _, event -> onPadKey(event) }
            // The upper screen of a DS takes no touches: the lower one does, on the bottom display.
            if (def.splitScreens) setOnTouchListener(ignoreTouches)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = quit()
        })
        lifecycle.addObserver(retroView)
        // OCR reads the view's own frame (see frame()), so its fractions are the view's.
        if (!def.splitScreens) overlay = OverlayView(this) { hit -> tapped(hit) }
        setContentView(FrameLayout(this).apply {
            addView(retroView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            this@CoreActivity.overlay?.let { addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        })
        retroView.requestFocus()
        retroView.afterFrame = {
            layoutWatch.afterFrame()
            capture?.let { if (it()) capture = null }
        }
        if (def.splitScreens) {
            lowerScreen = LowerScreen.displayFor(this)?.let { LowerScreen(this, it) }?.apply { show() }
        }

        bindService(Intent(this, CoreHostService::class.java), connection, Context.BIND_AUTO_CREATE)
        lifecycleScope.launch(Dispatchers.IO) { sendAudio() }
        lifecycleScope.launch(Dispatchers.IO) {
            // A crash or a kill loses at most this much play since the last save.
            while (isActive) {
                delay(SAVE_EVERY_MS)
                save()
            }
        }
        Log.i(TAG, "Playing ${rom.name} in ${def.displayName}; save at $saveFile")
    }

    /** A tap on the overlay: the reader looks the word up. */
    private fun tapped(hit: app.genkan.ocr.ScreenText.Hit) {
        try {
            host?.overlayTap(hit.text, hit.offset)
        } catch (e: RemoteException) {
            host = null
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val h = ICoreHost.Stub.asInterface(binder)
            host = h
            try {
                h.attach(session, romId, core.id, look.name)
            } catch (e: RemoteException) {
                Log.w(TAG, "Reader unreachable: $e")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            host = null
        }
    }

    /** The game's sound to the reader, a tenth of a second at a time. */
    private suspend fun sendAudio() {
        val samples = ShortArray(MAX_AUDIO_SAMPLES)
        while (lifecycleScope.coroutineContext.isActive) {
            delay(AUDIO_EVERY_MS)
            val n = GenkanTap.drainAudio(samples)
            val rate = GenkanTap.audioSampleRate()
            val h = host ?: continue
            if (n == 0 || rate == 0) continue
            val pcm = ByteArray(n * 2)
            for (i in 0 until n) {
                pcm[i * 2] = samples[i].toByte()
                pcm[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
            }
            try {
                h.audio(pcm, rate, System.currentTimeMillis())
            } catch (e: RemoteException) {
                host = null
            }
        }
    }

    private val session = object : ICoreSession.Stub() {
        override fun regions(): LongArray =
            GenkanTap.regions().flatMap { listOf(it.start, it.length, if (it.mirror) 1L else 0L) }.toLongArray()

        override fun read(address: Long, size: Int): ByteArray? {
            if (size !in 1..MAX_READ) return null
            val out = ByteArray(size)
            return out.takeIf { GenkanTap.readMemory(address, it, size) }
        }

        override fun frame(): Bundle? {
            // Both of a DS's screens, as the core drew them: the reader looks at either.
            if (core.splitScreens) return keptFrameBundle()
            val result = AtomicReference<Bundle?>()
            val done = CountDownLatch(1)
            retroView.grabFrame { pixels, width, height ->
                result.set(frameBundle(pixels, width, height))
                done.countDown()
            }
            // A paused game draws nothing: no frame then.
            return if (done.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS)) result.get() else null
        }

        override fun quit() {
            runOnUiThread { quit() }
        }

        override fun setLook(name: String) {
            val next = ScreenLook.of(name)
            runOnUiThread {
                look = next
                if (::retroView.isInitialized) retroView.shader = next.shader
            }
        }

        override fun showOverlay(screenTextJson: String) {
            val text = runCatching { app.genkan.ocr.ScreenText.fromJson(screenTextJson) }.getOrNull() ?: return
            runOnUiThread { overlay?.show(text) }
        }

        override fun nativeFrame(): Bundle? {
            if (core.splitScreens) return keptFrameBundle(scaled = false)
            GenkanTap.requestFrame()
            val until = System.currentTimeMillis() + FRAME_WAIT_MS
            while (System.currentTimeMillis() < until) {
                if (GenkanTap.frameSize() != null) return keptFrameBundle(scaled = false)
                Thread.sleep(10)
            }
            return null
        }

        override fun watchLayout(layoutJson: String, tableText: String, entryJson: String): Boolean =
            layoutWatch.set(layoutJson, tableText, entryJson)

        override fun videoSize(): Long = GenkanTap.videoRamSize()

        override fun saveStateTo(path: String): Long {
            // Only into Genkan's own snapshot folder, which both processes share.
            val file = java.io.File(path)
            if (file.parentFile?.canonicalPath != java.io.File(filesDir, "yomikata-snapshots").canonicalPath) return -1
            return try {
                val state = retroView.serializeState()
                if (state.isEmpty()) return -1
                java.util.zip.GZIPOutputStream(file.outputStream()).use { it.write(state) }
                state.size.toLong()
            } catch (e: Exception) {
                Log.w(TAG, "No savestate: $e")
                file.delete()
                -1
            }
        }

        override fun captureTo(path: String, address: Long, length: Int): Boolean {
            // Only into Genkan's own snapshot folder, which both processes share.
            val file = java.io.File(path)
            if (file.parentFile?.canonicalPath != java.io.File(filesDir, "yomikata-snapshots").canonicalPath) return false
            if (length <= 0) return false
            val ram = ByteArray(length)
            val done = CountDownLatch(1)
            captured = null
            var requested = false
            capture = capture@{
                // The request is made here, on the emulation thread, between two frames: the
                // next frame is the one kept, and its memory is what this copies after it.
                if (!requested) {
                    GenkanTap.requestFrame()
                    requested = true
                    return@capture false
                }
                if (GenkanTap.frameSize() == null) return@capture false
                // The emulation thread waits here, so nothing moves while the frame and the
                // memory are copied.
                captured = keptPixels()
                var at = 0
                val chunk = ByteArray(CAPTURE_CHUNK)
                while (at < length) {
                    val n = minOf(CAPTURE_CHUNK, length - at)
                    if (GenkanTap.readMemory(address + at, chunk, n)) chunk.copyInto(ram, at, 0, n)
                    at += n
                }
                done.countDown()
                true
            }
            if (!done.await(CAPTURE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                // A paused game draws no frame: nothing was taken.
                capture = null
                return false
            }
            return try {
                file.writeBytes(ram)
                true
            } catch (e: Exception) {
                Log.w(TAG, "No capture: $e")
                file.delete()
                false
            }
        }

        override fun keptFrame(): Bundle? = captured?.let { frameBundleOf(it, scaled = false) }

        override fun readVideo(offset: Long, size: Int): ByteArray? {
            if (size !in 1..MAX_READ) return null
            val out = ByteArray(size)
            return out.takeIf { GenkanTap.readVideoRam(offset, it, size) }
        }
    }

    /** GL's rows come bottom first: they go into shared memory top first, as a Bitmap wants them. */
    private fun frameBundle(pixels: ByteBuffer, width: Int, height: Int): Bundle {
        val row = width * 4
        val memory = SharedMemory.create("genkan-frame", row * height)
        val out = memory.mapReadWrite()
        val line = ByteArray(row)
        for (y in 0 until height) {
            pixels.position((height - 1 - y) * row)
            pixels.get(line)
            out.put(line)
        }
        SharedMemory.unmap(out)
        memory.setProtect(android.system.OsConstants.PROT_READ)
        return Bundle().apply {
            putInt(BuiltInPlayer.FRAME_WIDTH, width)
            putInt(BuiltInPlayer.FRAME_HEIGHT, height)
            putParcelable(BuiltInPlayer.FRAME_PIXELS, memory)
        }
    }

    /** The core's whole stacked frame, enlarged so small text is big enough to read. */
    private fun keptFrameBundle(scaled: Boolean = true): Bundle? = keptPixels()?.let { frameBundleOf(it, scaled) }

    /** A copy of the frame GenkanTap kept last, or null when it has none. */
    private fun keptPixels(): KeptFrame? {
        val (width, height) = GenkanTap.frameSize() ?: return null
        val pixels = ByteBuffer.allocateDirect(width * height * 4)
        if (!GenkanTap.copyFrame(pixels, 0, height)) return null
        return KeptFrame(pixels, width, height)
    }

    private class KeptFrame(val pixels: ByteBuffer, val width: Int, val height: Int)

    private fun frameBundleOf(frame: KeptFrame, scaled: Boolean): Bundle {
        val (pixels, width, height) = Triple(frame.pixels, frame.width, frame.height)
        val scale = if (scaled) (MIN_FRAME_SIDE + width - 1) / width else 1
        val row = width * 4
        val memory = SharedMemory.create("genkan-frame", row * scale * height * scale)
        val out = memory.mapReadWrite()
        val line = ByteArray(row)
        val wide = ByteArray(row * scale)
        for (y in 0 until height) {
            pixels.position(y * row)
            pixels.get(line)
            for (x in 0 until width) for (k in 0 until scale) line.copyInto(wide, (x * scale + k) * 4, x * 4, x * 4 + 4)
            repeat(scale) { out.put(wide) }
        }
        SharedMemory.unmap(out)
        memory.setProtect(android.system.OsConstants.PROT_READ)
        return Bundle().apply {
            putInt(BuiltInPlayer.FRAME_WIDTH, width * scale)
            putInt(BuiltInPlayer.FRAME_HEIGHT, height * scale)
            putParcelable(BuiltInPlayer.FRAME_PIXELS, memory)
        }
    }

    /** Writes the game's battery save when it changed since the last write. */
    @Synchronized
    private fun save() {
        if (!::retroView.isInitialized) return
        val sram = try {
            retroView.serializeSRAM(useEmulationThread = false)
        } catch (e: Exception) {
            Log.w(TAG, "No save from the core: $e")
            return
        }
        if (sram.isEmpty() || sram.contentEquals(lastSaved)) return
        val part = File(saveFile.parentFile, saveFile.name + ".part")
        saveFile.parentFile?.mkdirs()
        part.writeBytes(sram)
        if (part.renameTo(saveFile)) {
            lastSaved = sram
            Log.i(TAG, "Saved ${sram.size} bytes to $saveFile")
        }
    }

    private fun quit() {
        if (quitting) return
        quitting = true
        save()
        finish()
    }

    /**
     * The system's Back closes what is open on the bottom screen (the manual, in the launcher's
     * process) before it would quit the game: only a first press, never the Back the system makes
     * from an unhandled pad button (FLAG_FALLBACK), and the release of a press that closed
     * something is swallowed so it doesn't quit too. RestrictedApi is lint's false positive, as
     * in HomeActivity.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fallback = event.flags and KeyEvent.FLAG_FALLBACK != 0
        if (event.keyCode == KeyEvent.KEYCODE_BACK && backPress.onKey(event.action, event.repeatCount, fallback)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Asks the launcher's process to close what is open on the bottom screen; true when it did.
     * Waits at most [BOTTOM_BACK_WAIT_MS]: a slow or gone launcher counts as nothing closed, so
     * Back still quits the game. A launcher that answers later may still close the manual, so that
     * one press then both closes it and quits: accepted, as better than Back doing nothing.
     */
    private fun closedOnBottom(): Boolean {
        val h = host ?: return false
        val answer = AtomicBoolean(false)
        val done = CountDownLatch(1)
        Thread {
            answer.set(runCatching { h.bottomBack() }.getOrDefault(false))
            done.countDown()
        }.start()
        return done.await(BOTTOM_BACK_WAIT_MS, TimeUnit.MILLISECONDS) && answer.get()
    }

    /** Select held while Start goes down: save and quit. Seen before the core gets the keys. */
    private fun onPadKey(event: KeyEvent): Boolean {
        // Either stick's click steps the bottom display through what it can show (see [BottomCycle]):
        // on a DS its lower screen, the reader and the manual; elsewhere the reader and the manual.
        // A game that isn't a DS and has no manual keeps its stick clicks.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL || event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            // A PlayStation game's pad has stick clicks of its own: they stay the game's.
            if (!stickCycles) return false
            val lower = lowerScreen
            val h = host
            val hasManual = h?.let { runCatching { it.hasManual() }.getOrDefault(false) } == true
            if (lower == null && !hasManual) return false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val manualOpen = h?.let { runCatching { it.manualOpen() }.getOrDefault(false) } == true
                val now = when {
                    lower?.isShowing == true -> BottomCycle.GAME
                    manualOpen -> BottomCycle.MANUAL
                    else -> BottomCycle.READER
                }
                when (BottomCycle.next(now, ds = lower != null, hasManual = hasManual)) {
                    BottomCycle.GAME -> {
                        if (manualOpen) runCatching { h?.setManual(false) }
                        lower?.show()
                    }
                    BottomCycle.READER -> {
                        lower?.hide()
                        if (manualOpen) runCatching { h?.setManual(false) }
                    }
                    BottomCycle.MANUAL -> {
                        lower?.hide()
                        runCatching { h?.setManual(true) }
                    }
                }
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) selectDown = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START && event.action == KeyEvent.ACTION_DOWN && selectDown) {
            quit()
            return true
        }
        return false
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        lowerScreen?.dismiss()
        lowerScreen = null
        super.onDestroy()
        if (::core.isInitialized) {
            runCatching { unbindService(connection) }
            // The core can't be loaded again in this process: end it, and the reader sees the game go.
            if (isFinishing) Process.killProcess(Process.myPid())
        }
    }

    companion object {
        private const val TAG = "CoreActivity"
        const val EXTRA_ROM = "rom"
        const val EXTRA_ROM_ID = "rom_id"
        const val EXTRA_PLATFORM = "platform"
        /** The [ScreenLook] to draw the game with, by name. */
        const val EXTRA_LOOK = "look"
        private const val MAX_READ = 64 * 1024
        private const val FRAME_WAIT_MS = 500L
        /** How long Back waits for the launcher to say whether it closed something on the bottom screen. */
        private const val BOTTOM_BACK_WAIT_MS = 300L
        private const val CAPTURE_WAIT_MS = 2_000L
        private const val CAPTURE_CHUNK = 1 shl 20
        private const val AUDIO_EVERY_MS = 100L
        private const val MAX_AUDIO_SAMPLES = 48_000
        private const val SAVE_EVERY_MS = 30_000L
        /** A kept frame is enlarged to at least this wide for the reader's OCR. */
        private const val MIN_FRAME_SIDE = 720
        @SuppressLint("ClickableViewAccessibility")
        private val ignoreTouches = View.OnTouchListener { _, _ -> true }
    }
}

/**
 * What the bottom display shows during a game in the built-in player, as the stick click steps it
 * (on consoles without stick clicks of their own, [takesStickClicks]).
 */
enum class BottomCycle {
    /** A DS's lower screen, taking touches for the game. */
    GAME,
    READER,
    MANUAL,
    ;

    companion object {
        /** Consoles whose own pad has no stick clicks; a PlayStation's (DualShock) has. */
        private val NO_STICK_CLICKS = setOf("gb", "gbc", "gba", "nds", "psp")

        /** Whether the stick clicks step the bottom screen for [platform], rather than going to the game. */
        fun takesStickClicks(platform: String): Boolean = platform in NO_STICK_CLICKS

        /**
         * After [now]: on a DS ([ds]), its lower screen, the reader, the manual (when the game has
         * one), and round again; elsewhere the reader and the manual.
         */
        fun next(now: BottomCycle, ds: Boolean, hasManual: Boolean): BottomCycle = when (now) {
            GAME -> READER
            READER -> if (hasManual) MANUAL else if (ds) GAME else READER
            MANUAL -> if (ds) GAME else READER
        }
    }
}

/**
 * What the player does with a system Back: the first press asks [closedOnBottom] (the launcher
 * closes what is open on the bottom screen); when it closed something, that press's repeats and
 * release are taken too, so the same press doesn't also quit. A held press's repeats and the Back
 * the system makes from an unhandled pad button (fallback) never ask.
 */
internal class BackPress(private val closedOnBottom: () -> Boolean) {
    private var taken = false

    /** Whether the Back event ([action], [repeatCount], [fallback]) is taken here; false passes it on (and Back quits). */
    fun onKey(action: Int, repeatCount: Int, fallback: Boolean): Boolean {
        if (fallback) return false
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
            taken = closedOnBottom()
            return taken
        }
        if (action == KeyEvent.ACTION_UP && taken) {
            taken = false
            return true
        }
        return taken
    }
}
