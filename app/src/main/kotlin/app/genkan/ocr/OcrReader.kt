package app.genkan.ocr

import android.graphics.Bitmap
import android.util.Log
import app.genkan.capture.ScreenCapture
import app.genkan.reader.HookMessage
import app.genkan.home.LauncherPresence
import app.genkan.reader.NowPlaying
import app.genkan.reader.ReaderSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the current game's dialogue box off the top screen and hands each line to the reader.
 * Runs while it is switched on and the screen is being captured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OcrReader @Inject constructor(
    private val capture: ScreenCapture,
    private val mlKit: MlKitJapaneseOcr,
    private val meiki: MeikiOcr,
    private val settings: OcrSettings,
    private val session: ReaderSession,
    private val nowPlaying: NowPlaying,
    private val regions: OcrRegionDao,
    private val launcher: LauncherPresence,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val latch = FrameLatch()
    private var job: Job? = null
    private var lastText = ""

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** The area read for the current game: the one drawn for it, else the default. */
    val region: StateFlow<OcrRegion> = nowPlaying.game
        .flatMapLatest { game -> regions.region(game.key).map { it ?: OcrRegion.default(game.key) } }
        .stateIn(scope, SharingStarted.Eagerly, OcrRegion.default(nowPlaying.game.value.key))

    /** The engine in use: Meiki when chosen and bundled, else ML Kit. */
    val engine: StateFlow<OcrEngine> = settings.engine
        .map { if (it == OcrEngineChoice.MEIKI && meiki.available) meiki else mlKit }
        .stateIn(scope, SharingStarted.Eagerly, mlKit)

    /** Switches between Meiki and ML Kit (when Meiki is bundled). */
    fun nextEngine() {
        if (!meiki.available) return
        scope.launch {
            settings.setEngine(if (engine.value === meiki) OcrEngineChoice.ML_KIT else OcrEngineChoice.MEIKI)
            lastText = ""
            latch.reset()
        }
    }

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Each new line read, for sources that use OCR to find the text elsewhere (Azahar). */
    val lines: SharedFlow<String> = _lines.asSharedFlow()

    private val _latest = MutableStateFlow<String?>(null)
    /** The line on screen as OCR last read it, for a search that starts at the current line. */
    val latest: StateFlow<String?> = _latest.asStateFlow()

    private val _lastReadMs = MutableStateFlow<Long?>(null)
    /** How long the last OCR took, for the status line. */
    val lastReadMs: StateFlow<Long?> = _lastReadMs.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        if (on) start() else job?.cancel()
    }

    suspend fun saveRegion(region: OcrRegion) {
        region.cleaned()?.let { regions.save(it.copy(gameKey = nowPlaying.game.value.key)) }
        latch.reset()
    }

    suspend fun resetRegion() {
        regions.forget(nowPlaying.game.value.key)
        latch.reset()
    }

    /** Lines are read but not put in the backlog (another source gives them). */
    @Volatile var quiet: Boolean = false

    /** Turns a line OCR read into the game's exact text, or null to keep OCR's. */
    @Volatile var corrector: (suspend (String) -> String?)? = null

    private fun start() {
        if (job?.isActive == true) return
        latch.reset()
        job = scope.launch {
            while (isActive) {
                delay(INTERVAL_MS)
                // Nothing to read while Genkan's own launcher is on the top screen.
                if (!capture.running.value || launcher.inFront.value) continue
                val frame = capture.frame() ?: continue
                val box = crop(frame, region.value) ?: continue
                val pixels = IntArray(box.width * box.height)
                box.getPixels(pixels, 0, box.width, 0, 0, box.width, box.height)
                if (!latch.onFrame(FrameLatch.signature(pixels, box.width, box.height), System.currentTimeMillis())) continue
                read(box)
            }
        }
    }

    private suspend fun read(box: Bitmap) {
        val engine = engine.value
        val started = System.nanoTime()
        val text = try {
            OcrText.join(engine.read(box))
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed: $e")
            return
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        _lastReadMs.value = ms
        Log.i(TAG, "${engine.name} read ${text.length} characters in $ms ms: $text")
        if (text.isBlank() || text == lastText) return
        lastText = text
        // A memory source that has the game's script gives the line in the game's own words.
        val exact = corrector?.let { fix -> runCatching { fix(text) }.onFailure { Log.w(TAG, "Correction failed: $it") }.getOrNull() }
        if (!quiet) session.submit(HookMessage(exact ?: text))
        _latest.value = text
        _lines.tryEmit(text)
    }

    private fun crop(frame: Bitmap, region: OcrRegion): Bitmap? {
        val r = region.cleaned() ?: return null
        val left = (r.left * frame.width).toInt()
        val top = (r.top * frame.height).toInt()
        val width = ((r.right - r.left) * frame.width).toInt().coerceAtMost(frame.width - left)
        val height = ((r.bottom - r.top) * frame.height).toInt().coerceAtMost(frame.height - top)
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(frame, left, top, width, height)
    }

    private companion object {
        const val TAG = "OcrReader"
        const val INTERVAL_MS = 250L
    }
}
