package app.genkan.core

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
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private var host: ICoreHost? = null
    private var lastSaved: ByteArray? = null
    @Volatile private var quitting = false

    private var selectDown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rom = intent.getStringExtra(EXTRA_ROM)?.let(::File)
        val platform = intent.getStringExtra(EXTRA_PLATFORM).orEmpty()
        val def = CoreCatalog.forPlatform(platform)
        if (rom == null || !rom.isFile || def == null) {
            Log.w(TAG, "Nothing to play: $rom on $platform")
            finish()
            return
        }
        core = def
        romId = intent.getLongExtra(EXTRA_ROM_ID, 0)
        saveFile = CoreSaves.fileFor(platform, def, rom)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val data = GLRetroViewData(this).apply {
            // The linker finds a bare name among the APK's own libraries.
            coreFilePath = def.library
            gameFilePath = rom.absolutePath
            systemDirectory = File(filesDir, "system").apply { mkdirs() }.absolutePath
            savesDirectory = File(filesDir, "saves").apply { mkdirs() }.absolutePath
            variables = def.variables.map { (key, value) -> Variable(key, value) }.toTypedArray()
            saveRAMState = saveFile.takeIf { it.isFile }?.readBytes()?.also { lastSaved = it }
            shader = ShaderConfig.Default
            preferLowLatencyAudio = true
            rumbleEventsEnabled = false
        }
        retroView = GLRetroView(this, data).apply {
            isFocusable = true
            // Touch mode (the bottom screen was tapped last) must not leave it without focus.
            isFocusableInTouchMode = true
            setOnKeyListener { _, _, event -> onPadKey(event) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = quit()
        })
        lifecycle.addObserver(retroView)
        setContentView(FrameLayout(this).apply {
            addView(retroView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        retroView.requestFocus()

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

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val h = ICoreHost.Stub.asInterface(binder)
            host = h
            try {
                h.attach(session, romId, core.id)
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

    /** Select held while Start goes down: save and quit. Seen before the core gets the keys. */
    private fun onPadKey(event: KeyEvent): Boolean {
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
        private const val MAX_READ = 64 * 1024
        private const val FRAME_WAIT_MS = 500L
        private const val AUDIO_EVERY_MS = 100L
        private const val MAX_AUDIO_SAMPLES = 48_000
        private const val SAVE_EVERY_MS = 30_000L
    }
}
