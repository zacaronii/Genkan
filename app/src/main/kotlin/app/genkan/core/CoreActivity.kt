package app.genkan.core

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import android.widget.TextView
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
    private lateinit var saver: SramSaver
    /** The line of text over the game: unpacking, a failed load, a muted volume. */
    private lateinit var notice: TextView
    private lateinit var root: FrameLayout
    @Volatile private var quitting = false
    /** The game couldn't be played: its reason is on screen until the player closes. */
    @Volatile private var failed = false
    private var deviceCallback: AudioDeviceCallback? = null

    /** Select + a button: save states, fast-forward, quit ([PadChords]). */
    private val chords = PadChords()
    private lateinit var states: SaveStates
    /** The slot the state shortcuts save to and load from; [SaveStates.BEFORE_LOAD] undoes a load. */
    private var slot = 1
    private var fastForward = 1
    /** A state being saved or loaded: a quit waits for it, so the process isn't ended halfway through its file. */
    private var stateWork: kotlinx.coroutines.Job? = null
    /** Whether a Back closes something on the bottom screen first ([closedOnBottom]). */
    private val backPress = BackPress(::closedOnBottom)
    /** Whether the stick clicks step the bottom screen: on consoles whose own pad has none. */
    private var stickCycles = false
    private var lowerScreen: LowerScreen? = null
    /**
     * A capture waiting for its frame (see captureTo), run on the emulation thread after each
     * frame until it says it's done: the frame just drawn and the memory then are one moment.
     */
    /** Frames the game has run, the count a Yomikata script is told. */
    private var framesRun = 0L
    /** A savestate was loaded: the script is told before its next frame. */
    @Volatile private var scriptStateLoaded = false
    /** The last seconds of the game's picture (see [FrameRing]), for a card's animated Picture. */
    private val frameRing = FrameRing()
    /** How fast the game runs, a second at a time (see [PlayerStats]). */
    private val stats = PlayerStats()
    /** The reader's own work between frames since the last second's stats: the script, the ring's shots. */
    private val scriptNanos = java.util.concurrent.atomic.AtomicLong()
    private val ringNanos = java.util.concurrent.atomic.AtomicLong()
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
        states = SaveStates(SaveStates.dirFor(File(filesDir, SaveStates.FOLDER), def.id, GameFile.keyFor(romId, rom)), def.id)
        fastForward = PadChords.fastForwardSpeed(platform)

        notice = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
            textSize = 18f
            setPadding(32, 24, 32, 24)
            visibility = View.GONE
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(notice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.START))
        }
        setContentView(root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // A zipped game is unpacked first: the cores read the file as it is.
        lifecycleScope.launch {
            val cacheRoot = GameFile.cacheRoot(noBackupFilesDir)
            if (!def.takes(rom.name)) say("Unpacking…")
            val prepared = kotlinx.coroutines.withContext(Dispatchers.IO) { GameFile.prepare(def, rom, cacheRoot, GameFile.keyFor(romId, rom)) }
            when (prepared) {
                is GameFile.Prepared.Unplayable -> fail(prepared.reason)
                is GameFile.Prepared.Ready -> {
                    say(prepared.note, NOTE_MS)
                    play(def, rom, prepared.file)
                }
            }
        }
    }

    /** Starts [def] on [game], the file it plays (unpacked from [rom] when that is a zip). */
    private fun play(def: CoreDef, rom: File, game: File) {
        // Read now, from the file the core plays: the reader asks for it to find the game's catalog entry.
        cartridge = if (def.id == "bsnes_mercury") SnesCartridge.of(game) else null
        // The process may have played a game before this one: its sound problem isn't this one's.
        GenkanTap.clearAudioProblem()
        val system = File(filesDir, "system").apply { mkdirs() }
        def.assets?.let { (zip, folder) -> CoreAssets.unpack(this, zip, File(system, folder)) }

        GenkanTap.setSplitScreens(def.splitScreens)
        val data = GLRetroViewData(this).apply {
            // The linker finds a bare name among the APK's own libraries.
            coreFilePath = def.library
            gameFilePath = game.absolutePath
            systemDirectory = system.absolutePath
            savesDirectory = CoreSaves.directoryFor(def, File(filesDir, "saves/${def.id}")).apply { mkdirs() }.absolutePath
            variables = def.variables.map { (key, value) -> Variable(key, value) }.toTypedArray()
            portDevices = def.portDevices.toIntArray()
            sticksOnly = def.sticksOnly
            saveRAMState = saveFile.takeIf { it.isFile }?.readBytes()
            shader = look.shader(::readAsset)
            // Per console (Settings > Playing): low latency unless it was switched off there.
            preferLowLatencyAudio = intent.getBooleanExtra(EXTRA_LOW_LATENCY, true)
            rumbleEventsEnabled = false
        }
        saver = SramSaver(saveFile, loaded = ::gameLoaded, read = { retroView.serializeSRAM(useEmulationThread = false) }, last = data.saveRAMState)
        retroView = GLRetroView(this, data).apply {
            isFocusable = true
            // Touch mode (the bottom screen was tapped last) must not leave it without focus.
            isFocusableInTouchMode = true
            setOnKeyListener { _, _, event -> onPadKey(event) }
            // Settings > Playing: which of the device's face buttons is the core's A.
            val aOnRight = intent.getBooleanExtra(EXTRA_A_ON_RIGHT, false)
            keyMap = { FaceButtons.map(it, aOnRight) }
            // The upper screen of a DS takes no touches: the lower one does, on the bottom display.
            if (def.splitScreens) setOnTouchListener(ignoreTouches)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = quit()
        })
        lifecycle.addObserver(retroView)
        // OCR reads the view's own frame (see frame()), so its fractions are the view's.
        if (!def.splitScreens) overlay = OverlayView(this) { hit -> tapped(hit) }
        root.addView(retroView, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay?.let { root.addView(it, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        retroView.requestFocus()
        // A load that fails says why on screen, then closes: never a silent black screen.
        lifecycleScope.launch { retroView.getGLRetroErrors().collect { code -> fail(PlayerMessages.loadError(code, def.displayName)) } }
        logOutputs()
        watchSound()
        retroView.afterFrame = {
            capture?.let { if (it()) capture = null }
            // A Yomikata script reads the game between two frames (its events, logged for now).
            if (scriptStateLoaded) {
                scriptStateLoaded = false
                GenkanTap.scriptStateLoaded()
                try {
                    host?.scriptStateLoaded()
                } catch (e: RemoteException) {
                    host = null
                }
            }
            val scriptStarted = System.nanoTime()
            val events = GenkanTap.scriptFrame(framesRun++)
            scriptNanos.addAndGet(System.nanoTime() - scriptStarted)
            if (events.isNotEmpty()) {
                // The script's own log lines go to logcat here; everything goes to the reader.
                events.filter { it.first == ScriptEvents.LOG }.forEach { Log.i(TAG, "Script log: ${it.second}") }
                try {
                    host?.scriptEvents(events.map { "${it.first}\t${it.second}" }.toTypedArray())
                } catch (e: RemoteException) {
                    host = null
                }
            }
            // The last seconds of the picture, small, for a card's animated Picture.
            val now = android.os.SystemClock.uptimeMillis()
            if (frameRing.due(now)) {
                val started = System.nanoTime()
                retroView.readShownFrameScaled(FrameRing.WIDTH)?.let { frameRing.add(it, now, System.nanoTime() - started) }
                ringNanos.addAndGet(System.nanoTime() - started)
            }
        }
        lifecycleScope.launch(Dispatchers.IO) { measure() }
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

    /** Whether the core has a game in it: until then (and for good, when the load failed) it isn't asked for its memory. */
    private fun gameLoaded(): Boolean = ::retroView.isInitialized && retroView.gameLoaded

    /**
     * Says "Media volume is off" for a few seconds at the start when it is, and again each time it
     * goes to zero or mute while the game runs (another app on the device may set it as the player
     * starts); a volume muted on purpose isn't kept on screen. Says, too, when the sound stream
     * wouldn't open or stopped ([GenkanTap.audioProblem]).
     */
    private fun watchSound() {
        val audio = getSystemService(AudioManager::class.java)
        lifecycleScope.launch {
            var off = false
            var problem: String? = null
            while (isActive) {
                val now = PlayerMessages.volumeOff(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.isStreamMute(AudioManager.STREAM_MUSIC))
                if (now != off && !quitting) {
                    off = now
                    Log.i(TAG, if (now) "Media volume is off" else "Media volume is back")
                    if (now) say(PlayerMessages.MEDIA_VOLUME_OFF, NOTE_MS) else if (notice.text == PlayerMessages.MEDIA_VOLUME_OFF) say(null)
                }
                val nowProblem = GenkanTap.audioProblem()
                if (nowProblem != problem && !quitting) {
                    problem = nowProblem
                    nowProblem?.let {
                        Log.w(TAG, "No sound: $it")
                        say(PlayerMessages.noSound(it), NOTE_MS)
                    }
                }
                delay(VOLUME_EVERY_MS)
            }
        }
    }

    /** Shows [text] over the game, for [forMs] or until the next; null hides it. */
    private fun say(text: String?, forMs: Long? = null) {
        notice.text = text
        notice.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null && forMs != null) {
            lifecycleScope.launch {
                delay(forMs)
                if (notice.text == text) say(null)
            }
        }
    }

    /** The game can't be played: says why, then closes. Nothing is saved: nothing was played. */
    private fun fail(reason: String) {
        if (quitting) return
        quitting = true
        failed = true
        Log.w(TAG, "Can't play: $reason")
        say(reason)
        lifecycleScope.launch {
            delay(FAIL_SHOW_MS)
            finish()
        }
    }

    /**
     * The outputs sound can go to, by id and type, and where media goes now; and each output
     * added or removed while the game runs. The audio log names the stream's device by id: the
     * two together say whether the stream plays where media is routed.
     */
    private fun logOutputs() {
        val audio = getSystemService(AudioManager::class.java)
        fun describe(devices: Array<AudioDeviceInfo>) = devices.joinToString { "${it.id} ${PlayerMessages.outputType(it.type)}" }
        fun mediaRoute() = audio.getAudioDevicesForAttributes(
            android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build(),
        ).joinToString { "${it.id} ${PlayerMessages.outputType(it.type)}" }
        Log.i(TAG, "Audio outputs: ${describe(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))}; media goes to ${mediaRoute()}")
        deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) {
                Log.i(TAG, "Audio outputs added: ${describe(added.filter { it.isSink }.toTypedArray())}; media goes to ${mediaRoute()}")
            }

            override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) {
                Log.i(TAG, "Audio outputs removed: ${describe(removed.filter { it.isSink }.toTypedArray())}; media goes to ${mediaRoute()}")
            }
        }.also { audio.registerAudioDeviceCallback(it, null) }
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

    /** The Super Famicom game's header id, read as it starts. */
    @Volatile private var cartridge: SnesCartridge.Id? = null

    private val session = object : ICoreSession.Stub() {
        override fun cartridgeId(): String? = cartridge?.let { "${it.id} ${it.headerCrc32}" }

        override fun loadScript(name: String, source: ByteArray, data: Bundle, gameId: String, buildId: String?): String? {
            if (!gameLoaded()) return "no game is running"
            val files = data.keySet().associateWith { data.getByteArray(it) ?: ByteArray(0) }
            val result = AtomicReference<String?>()
            val done = CountDownLatch(1)
            // On the emulation thread, between two frames: the script checks the game's memory as it loads.
            retroView.queueEvent {
                result.set(
                    GenkanTap.scriptLoad(
                        name, source, files, ScriptEvents.catalogPlatform(core.platforms.first()), gameId, buildId,
                        core.pointerMask, core.ramBase,
                    ),
                )
                done.countDown()
            }
            if (!done.await(SCRIPT_LOAD_WAIT_MS, TimeUnit.MILLISECONDS)) return "the game didn't run a frame to load it in"
            val error = result.get()
            Log.i(TAG, if (error == null) "Script $name loaded for $gameId" else "Script $name didn't load: $error")
            return error
        }

        override fun scriptLevel(): Int = GenkanTap.scriptLevel()

        override fun pictureBounds(): FloatArray? = GenkanTap.pictureBounds()

        override fun unloadScript() {
            retroView.queueEvent { GenkanTap.scriptUnload() }
        }

        override fun recentGif(path: String, millis: Int): Boolean {
            // Only into the Anki media folder, which both processes share.
            val file = java.io.File(path)
            if (file.parentFile?.canonicalPath != java.io.File(filesDir, "anki-media").canonicalPath) return false
            val frames = frameRing.recent(millis.toLong(), android.os.SystemClock.uptimeMillis())
            if (frames.size < MIN_GIF_FRAMES) return false
            return try {
                file.outputStream().buffered().use { app.genkan.anki.GifEncoder.encode(frames, FrameRing.INTERVAL_MS.toInt(), it) }
                true
            } catch (e: Exception) {
                Log.w(TAG, "No GIF: $e")
                file.delete()
                false
            }
        }

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
            if (!gameLoaded()) return null
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
                if (::retroView.isInitialized) retroView.shader = next.shader(::readAsset)
            }
        }

        override fun showPanel(panelJson: String) {
            val panel = OverlayPanel.fromJson(panelJson)
            runOnUiThread { overlay?.showPanel(panel) }
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

        override fun videoSize(): Long = GenkanTap.videoRamSize()

        override fun saveStateTo(path: String): Long {
            // Only into Genkan's own snapshot folder, which both processes share.
            val file = java.io.File(path)
            if (file.parentFile?.canonicalPath != java.io.File(filesDir, "yomikata-snapshots").canonicalPath) return -1
            if (!gameLoaded()) return -1
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
            // The video RAM too, from the same frame, when the core gives it out.
            val vram = GenkanTap.videoRamSize().takeIf { it in 1..MAX_VRAM }?.let { ByteArray(it.toInt()) }
            var read = false
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
                // The emulation thread waits here, so nothing moves while the frame and the
                // memory are copied. A core that draws with GL (Dolphin, PPSSPP) hands over no
                // pixels to keep: the frame is read off the screen instead, as shown.
                captured = keptPixels() ?: shownPixels() ?: return@capture false
                var at = 0
                val chunk = ByteArray(CAPTURE_CHUNK)
                while (at < length) {
                    val n = minOf(CAPTURE_CHUNK, length - at)
                    if (GenkanTap.readMemory(address + at, chunk, n)) {
                        chunk.copyInto(ram, at, 0, n)
                        read = true
                    }
                    at += n
                }
                if (vram != null && !GenkanTap.readVideoRam(0, vram, vram.size)) vram.fill(0)
                done.countDown()
                true
            }
            if (!done.await(CAPTURE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                // A paused game draws no frame: nothing was taken.
                capture = null
                return false
            }
            // Memory the core doesn't map there: no capture, rather than a dump of zeros.
            if (!read) return false
            return try {
                file.writeBytes(ram)
                vram?.let { java.io.File(path + BuiltInPlayer.VRAM_SUFFIX).writeBytes(it) }
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

    /** The frame on screen, top row first, read on the emulation thread right after it was drawn. */
    private fun shownPixels(): KeptFrame? {
        val (bottomUp, width, height) = retroView.readShownFrame() ?: return null
        return KeptFrame(TopDown.of(bottomUp, width, height), width, height)
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

    /** Writes the game's battery save when it changed since the last write; not before a game has loaded. */
    private fun save() {
        if (::saver.isInitialized) saver.save()
    }

    private fun quit() {
        // A failed load's reason is up: Back closes at once, with nothing to save.
        if (failed) {
            finish()
            return
        }
        if (quitting) return
        // A state still being written finishes first: the process ends with the activity.
        stateWork?.takeIf { it.isActive }?.let { work ->
            lifecycleScope.launch {
                work.join()
                quit()
            }
            return
        }
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
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val verdict = chords.key(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)
        verdict.started?.let(::startShortcut)
        verdict.ended.forEach(::endShortcut)
        if (verdict.tapSelect) tapSelect(event)
        return verdict.consume
    }

    private fun startShortcut(shortcut: PadChords.Shortcut) {
        when (shortcut) {
            PadChords.Shortcut.QUIT -> quit()
            PadChords.Shortcut.SAVE_STATE -> if (stateWork?.isActive != true) stateWork = lifecycleScope.launch(Dispatchers.IO) { saveState() }
            PadChords.Shortcut.LOAD_STATE -> if (stateWork?.isActive != true) stateWork = lifecycleScope.launch(Dispatchers.IO) { loadState() }
            PadChords.Shortcut.NEXT_SLOT -> pickSlot(slot + 1)
            PadChords.Shortcut.PREVIOUS_SLOT -> pickSlot(slot - 1)
            PadChords.Shortcut.FAST_FORWARD -> if (gameLoaded()) {
                // Muted while it runs: the cheapest way, as the sound would play at the game's speed.
                retroView.audioEnabled = false
                retroView.frameSpeed = fastForward
            }
        }
    }

    private fun endShortcut(shortcut: PadChords.Shortcut) {
        if (shortcut == PadChords.Shortcut.FAST_FORWARD && gameLoaded()) {
            retroView.frameSpeed = 1
            retroView.audioEnabled = true
        }
    }

    /** Select let go with no shortcut used: the game gets its press, held for a few frames so it sees it. */
    private fun tapSelect(event: KeyEvent) {
        if (!gameLoaded()) return
        val port = (event.device?.controllerNumber ?: 0) - 1
        if (port < 0) return
        retroView.sendKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT, port)
        lifecycleScope.launch {
            delay(SELECT_TAP_MS)
            retroView.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_SELECT, port)
        }
    }

    /** Slots 1 to 4, then the state before the last load; the note says what each holds. */
    private fun pickSlot(next: Int) {
        slot = Math.floorMod(next, SaveStates.SLOTS + 1)
        val name = slotName(slot)
        say(states.savedAt(slot)?.let { "$name · saved ${clock(it)}" } ?: "$name · empty", NOTE_MS)
    }

    private fun slotName(slot: Int) = if (slot == SaveStates.BEFORE_LOAD) "Before the last load" else "Slot $slot"

    private fun clock(at: Long): String =
        android.text.format.DateUtils.formatDateTime(this, at, android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)

    /** Saves the game's state into the slot picked; what it held stays as its .bak. */
    private fun saveState() {
        if (!gameLoaded()) return
        if (slot == SaveStates.BEFORE_LOAD) return runOnUiThread { say("Pick slot 1 to ${SaveStates.SLOTS} to save into", NOTE_MS) }
        val note = try {
            val state = retroView.serializeState()
            if (state.isEmpty()) "${core.displayName} can't save its state" else {
                states.write(slot, state)
                "Saved to slot $slot · ${clock(System.currentTimeMillis())}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "No state saved: $e")
            "Couldn't save to slot $slot: ${e.message}"
        }
        Log.i(TAG, note)
        runOnUiThread { say(note, NOTE_MS) }
    }

    /**
     * Loads the slot picked. The state the game is in goes to [SaveStates.BEFORE_LOAD] first, and the
     * battery save is written if the game changed it; a load that fails leaves the game as it was.
     */
    private fun loadState() {
        if (!gameLoaded()) return
        val name = slotName(slot)
        val note = when (val read = states.read(slot)) {
            SaveStates.Read.Empty -> "$name is empty"
            is SaveStates.Read.OtherCore -> "Couldn't load $name: it was saved by another core (${read.core})"
            is SaveStates.Read.Unreadable -> "Couldn't load $name: ${read.reason}"
            is SaveStates.Read.State -> {
                save()
                val before = retroView.serializeState()
                if (slot != SaveStates.BEFORE_LOAD && before.isNotEmpty()) runCatching { states.write(SaveStates.BEFORE_LOAD, before) }
                if (retroView.unserializeState(read.bytes)) {
                    saver.stateLoaded()
                    // The script forgets what it believed was on screen before its next frame.
                    scriptStateLoaded = true
                    "Loaded $name · saved ${clock(read.savedAt)}"
                } else {
                    // A core that refuses a state may have read part of it: the game goes back to where it was.
                    if (before.isNotEmpty() && retroView.unserializeState(before)) scriptStateLoaded = true
                    "Couldn't load $name: ${core.displayName} refused it"
                }
            }
        }
        Log.i(TAG, note)
        runOnUiThread { say(note, NOTE_MS) }
    }


    /** An asset's text: a RetroArch shader's file for a [ScreenLook]. */
    private fun readAsset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        deviceCallback?.let { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        lowerScreen?.dismiss()
        lowerScreen = null
        super.onDestroy()
        if (::core.isInitialized) {
            runCatching { unbindService(connection) }
            // The core can't be loaded again in this process: end it, and the reader sees the game go.
            if (isFinishing) Process.killProcess(Process.myPid())
        }
    }

    /** Each second: how fast the game ran (logged in debug builds, with the CPU's clock and heat). */
    private suspend fun measure() {
        var last = System.nanoTime()
        val threads = ThreadLoad()
        threads.sample(0)
        GenkanTap.frameStats()
        while (true) {
            delay(STATS_EVERY_MS)
            val now = System.nanoTime()
            val v = GenkanTap.frameStats()
            val row = stats.add(
                PlayerStats.Sample(
                    runs = v[0], runNanos = v[1], longestNanos = v[2], p95Nanos = v[3], steps = v[4], contentFpsMilli = v[5],
                    elapsedNanos = now - last, scriptNanos = scriptNanos.getAndSet(0), ringNanos = ringNanos.getAndSet(0),
                    busiest = threads.sample(now - last).firstOrNull(), gpuBusyPercent = DeviceHeat.gpuBusyPercent(), gpuMhz = DeviceHeat.gpuMhz(),
                ),
            )
            last = now
            if (row != null && app.genkan.BuildConfig.DEBUG) {
                Log.i(STATS_TAG, "${row.line()} | cpu ${DeviceHeat.fastestMhz() ?: "?"} MHz, ${DeviceHeat.hottestCpuC() ?: "?"} °C" + if (stats.behind) " | behind" else "")
            }
        }
    }

    companion object {
        private const val TAG = "CoreActivity"
        private const val STATS_TAG = "PlayerStats"
        private const val STATS_EVERY_MS = 1_000L
        const val EXTRA_ROM = "rom"
        const val EXTRA_ROM_ID = "rom_id"
        const val EXTRA_PLATFORM = "platform"
        /** The [ScreenLook] to draw the game with, by name. */
        const val EXTRA_LOOK = "look"
        /** Whether to ask for a low-latency sound stream (the console's setting); yes when absent. */
        const val EXTRA_LOW_LATENCY = "low_latency"
        /** Whether the device's A is its right button; no (the bottom one) when absent. */
        const val EXTRA_A_ON_RIGHT = "a_on_right"
        private const val MAX_READ = 64 * 1024
        private const val FRAME_WAIT_MS = 500L
        /** How long Back waits for the launcher to say whether it closed something on the bottom screen. */
        private const val BOTTOM_BACK_WAIT_MS = 300L
        private const val CAPTURE_WAIT_MS = 2_000L
        private const val CAPTURE_CHUNK = 1 shl 20
        /** The most video RAM a capture copies (a PS1's is 1 MB). */
        private const val MAX_VRAM = 4L shl 20
        /** A script's load, waiting for the emulation thread. */
        private const val SCRIPT_LOAD_WAIT_MS = 5_000L
        /** Fewer frames than this is a still, which the card takes as WebP instead. */
        private const val MIN_GIF_FRAMES = 5
        private const val AUDIO_EVERY_MS = 100L
        /** How long a failed load's reason stays up before the player closes. */
        private const val FAIL_SHOW_MS = 5_000L
        /** How long a note (which file of a zip is played) stays up. */
        private const val NOTE_MS = 4_000L
        /** How often the media volume is looked at, for the note while it's off. */
        private const val VOLUME_EVERY_MS = 1_000L
        private const val MAX_AUDIO_SAMPLES = 48_000
        private const val SAVE_EVERY_MS = 30_000L
        /** How long a lone Select press is held for the game: a few frames, so a core polling once a frame sees it. */
        private const val SELECT_TAP_MS = 80L
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
        private val NO_STICK_CLICKS = setOf("gb", "gbc", "gba", "snes", "sfam", "nds", "psp")

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
