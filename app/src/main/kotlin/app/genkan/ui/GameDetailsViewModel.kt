package app.genkan.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadQueue
import app.genkan.home.LauncherPresence
import app.genkan.launch.LocalShelves
import app.genkan.library.FocusedGame
import app.genkan.library.GameEntity
import app.genkan.play.PlaySessionDao
import app.genkan.play.PlayTotals
import app.genkan.romm.RomMApiFactory
import app.genkan.romm.RomMImageAuthInterceptor
import app.genkan.romm.RomMSession
import app.genkan.saves.SaveStatus
import app.genkan.saves.SaveSync
import app.genkan.stats.ReadingDao
import app.genkan.stats.ReadingGameTotals
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject

/** What the bottom screen shows: the focused game while browsing, the reader otherwise. */
enum class CompanionMode { DETAILS, READER }

/**
 * The focused game's artwork as image models (URLs on the RomM server), ready for Coil. All of
 * it is empty for local games and when signed out.
 */
data class GameArt(
    /** The disc or cartridge cut-out. */
    val physical: Any? = null,
    /** The title logo. */
    val logo: Any? = null,
    val fanart: Any? = null,
    val screenshots: List<Any> = emptyList(),
    /** Whether the server has a manual for the game. */
    val hasManual: Boolean = false,
)

data class GameDetails(
    val focus: FocusedGame.Focus,
    val play: PlayTotals?,
    val reading: ReadingGameTotals,
    val download: DownloadEntity?,
    val save: SaveStatus? = null,
    val art: GameArt = GameArt(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GameDetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    launcher: LauncherPresence,
    private val focusedGame: FocusedGame,
    playSessions: PlaySessionDao,
    readingDao: ReadingDao,
    private val downloadQueue: DownloadQueue,
    saveSync: SaveSync,
    private val session: RomMSession,
    httpClient: OkHttpClient,
) : ViewModel() {

    /** Set when the backlog is asked for while browsing; cleared whenever the top screen changes. */
    private val readerPinned = MutableStateFlow(false)

    /** Carries the RomM sign-in, as Coil's loader does, for fetching manuals. */
    private val assetClient = httpClient.newBuilder()
        .addInterceptor(RomMImageAuthInterceptor(session::credentials))
        .build()

    val mode: StateFlow<CompanionMode> =
        combine(launcher.inFront, focusedGame.focus, readerPinned) { inFront, focus, pinned ->
            if (inFront && focus != null && !pinned) CompanionMode.DETAILS else CompanionMode.READER
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CompanionMode.READER)

    /** Whether the reader can switch back to the focused game. */
    val canShowDetails: StateFlow<Boolean> =
        combine(launcher.inFront, focusedGame.focus) { inFront, focus -> inFront && focus != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val details: StateFlow<GameDetails?> = focusedGame.focus
        .flatMapLatest { focus ->
            if (focus == null) return@flatMapLatest flowOf(null)
            val game = focus.game
            val art = artFor(game)
            // Play sessions and downloads exist only for RomM games; reading is kept per game key.
            val play = if (LocalShelves.isLocal(game)) flowOf(null) else playSessions.totals(game.id)
            combine(
                play,
                readingDao.gameTotals("game:${game.id}"),
                downloadQueue.downloads.map { it[game.id] },
                saveSync.status.map { it[game.id] },
            ) { p, r, d, s -> GameDetails(focus, p, r, d, s, art) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _notice = MutableStateFlow<String?>(null)
    /** A short message under the buttons, e.g. while the manual downloads. Clears itself. */
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var noticeJob: Job? = null
    private var manualJob: Job? = null

    private val _manual = MutableStateFlow<File?>(null)
    /** The fetched manual the bottom screen is showing in [ManualViewer], or null. */
    val manual: StateFlow<File?> = _manual.asStateFlow()

    init {
        viewModelScope.launch { launcher.inFront.drop(1).collect { readerPinned.value = false } }
        // A message about one game means nothing once another is focused.
        viewModelScope.launch {
            focusedGame.focus.map { it?.game?.id }.drop(1).collect {
                manualJob?.cancel()
                _manual.value = null
                noticeJob?.cancel()
                _notice.value = null
            }
        }
    }

    fun showReader() {
        readerPinned.value = true
    }

    fun showDetails() {
        readerPinned.value = false
    }

    /** Play, download or cancel, exactly as A on the top screen would. */
    fun play() {
        details.value?.focus?.game?.let { focusedGame.requestPlay(it.id) }
    }

    /** Deletes the focused game's download; the screen asks for a second tap first. */
    fun deleteDownload() {
        val game = details.value?.focus?.game ?: return
        downloadQueue.delete(game.id)
        show("Deleted ${game.name} from this device")
    }

    /**
     * Fetches the focused game's manual into the cache (once per version of it) and then opens
     * it in the in-app viewer, through [manual].
     */
    fun openManual() {
        val game = details.value?.focus?.game ?: return
        val url = session.currentAccount()?.let { RomMApiFactory.assetUrl(it.baseUrl, game.manualPath) } ?: return
        if (manualJob?.isActive == true) return
        val folder = File(context.cacheDir, MANUALS_FOLDER)
        val file = File(folder, manualFileName(game))
        manualJob = viewModelScope.launch {
            if (!file.isFile || file.length() == 0L) {
                noticeJob?.cancel()
                _notice.value = "Fetching the manual…"
                val fetched = withContext(Dispatchers.IO) {
                    runCatching {
                        fetch(url, file)
                        // Earlier versions of this game's manual are no longer wanted.
                        folder.listFiles { old -> old.name.startsWith("${game.id}-") && old.name != file.name }?.forEach { it.delete() }
                    }
                }
                fetched.exceptionOrNull()?.let { show("Couldn't fetch the manual: ${it.message}"); return@launch }
                _notice.value = null
            }
            _manual.value = file
        }
    }

    /**
     * Closes the manual viewer. [problem], when given, says why (the file wouldn't open); the
     * cached file is then deleted, so the next tap fetches it again.
     */
    fun closeManual(problem: String? = null) {
        val file = _manual.value
        _manual.value = null
        if (problem == null) return
        file?.let { viewModelScope.launch(Dispatchers.IO) { it.delete() } }
        show(problem)
    }

    /** A message for a few seconds, e.g. that the manual couldn't be fetched. */
    fun show(message: String) {
        noticeJob?.cancel()
        _notice.value = message
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _notice.value = null
        }
    }

    private fun artFor(game: GameEntity): GameArt {
        val baseUrl = session.currentAccount()?.baseUrl
        if (LocalShelves.isLocal(game) || baseUrl == null) return GameArt()
        val url = { path: String? -> RomMApiFactory.assetUrl(baseUrl, path) }
        return GameArt(
            physical = url(game.physicalPath),
            logo = url(game.logoPath),
            fanart = url(game.fanartPath),
            screenshots = game.screenshotPathList.mapNotNull(url),
            hasManual = game.manualPath != null,
        )
    }

    /** Downloads [url] to [file] through a .part file, so a cut-off download is never opened. */
    private fun fetch(url: String, file: File) {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        assetClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("the server answered ${response.code}")
            part.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        }
        if (!part.renameTo(file)) throw IOException("couldn't save it")
    }

    companion object {
        private const val NOTICE_MS = 4_000L
        private const val MANUALS_FOLDER = "manuals"

        /**
         * The cached manual's file name: the game's id, then a hash of what identifies this
         * version of the manual, so a changed manual is fetched again. Games carry no updated
         * time, so the hash covers the manual's path (RomM adds a timestamp to some asset paths)
         * and the game's file size.
         */
        fun manualFileName(game: GameEntity): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("${game.manualPath}|${game.fsSizeBytes}".toByteArray())
            return "${game.id}-${digest.take(6).joinToString("") { "%02x".format(it) }}.pdf"
        }
    }
}
