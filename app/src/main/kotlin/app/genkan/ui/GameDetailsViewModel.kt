package app.genkan.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.art.ArtLoaders
import app.genkan.art.RomMArt
import app.genkan.core.CoreCatalog
import app.genkan.core.PlayerSettings
import app.genkan.core.ScreenLook
import app.genkan.core.ScreenLooks
import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadQueue
import app.genkan.home.LauncherPresence
import app.genkan.launch.LocalShelves
import app.genkan.launch.SteamDetails
import app.genkan.launch.SteamStore
import app.genkan.launch.applyTo
import app.genkan.library.FavouriteDao
import app.genkan.library.Favourites
import app.genkan.library.FocusedGame
import app.genkan.library.GameEntity
import app.genkan.manual.ManualFetch
import app.genkan.manual.ManualFetcher
import app.genkan.manual.Manuals
import app.genkan.play.PlaySessionDao
import app.genkan.play.PlayTotals
import app.genkan.romm.RomMSession
import app.genkan.saves.SaveStatus
import app.genkan.saves.SaveSync
import app.genkan.stats.ReadingDao
import app.genkan.stats.ReadingGameTotals
import app.genkan.words.GameLookupDao
import app.genkan.words.GameLookupTotals
import app.genkan.yomikata.TextSource
import app.genkan.yomikata.YomikataCatalog
import app.genkan.yomikata.YomikataIds
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    /** Whether the game is on the "Favourites" shelf. */
    val favourite: Boolean = false,
    /** The words looked up while reading it. */
    val lookups: GameLookupTotals = GameLookupTotals(0, 0),
    /** How this build would read its text, once it has run and the catalog knows it. */
    val textSource: TextSource? = null,
    /** How the built-in player draws it, when the player is on and runs its console. */
    val look: GameLook? = null,
    /** The game's own step on its core's ladder, when it has one (learned, or set). */
    val tuning: GameTuningNote? = null,
)

/** A game's own ladder step ([label]: what it does), and whether learning chose it. */
data class GameTuningNote(val label: String, val learned: Boolean)

/** A game's own look in the built-in player ([own], null: its console's) and its console's. */
data class GameLook(val own: ScreenLook?, val console: ScreenLook)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GameDetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val launcher: LauncherPresence,
    private val focusedGame: FocusedGame,
    playSessions: PlaySessionDao,
    readingDao: ReadingDao,
    private val downloadQueue: DownloadQueue,
    private val favouriteDao: FavouriteDao,
    private val gameLookups: GameLookupDao,
    private val yomikataIds: YomikataIds,
    private val catalog: YomikataCatalog,
    private val playerSettings: PlayerSettings,
    private val screenLooks: ScreenLooks,
    saveSync: SaveSync,
    private val session: RomMSession,
    private val manuals: Manuals,
    private val steamStore: SteamStore,
    private val artLoaders: ArtLoaders,
    private val tuningStore: app.genkan.core.TuningStore,
) : ViewModel() {

    /** Set when the backlog is asked for while browsing; cleared whenever the top screen changes. */
    private val readerPinned = MutableStateFlow(false)

    val mode: StateFlow<CompanionMode> =
        combine(launcher.inFront, focusedGame.focus, readerPinned, focusedGame.launching) { inFront, focus, pinned, launching ->
            // A game being started takes the bottom screen to the reader as the insert animation begins.
            if (inFront && focus != null && !pinned && launching == null) CompanionMode.DETAILS else CompanionMode.READER
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
            // Play sessions exist for RomM and PC games, downloads only for RomM's; reading is kept per game key.
            val play = if (LocalShelves.isLocal(game) && game.platformSlug != LocalShelves.PC_SLUG) flowOf(null) else playSessions.totals(game.id)
            // A PC game's details come from Steam's store: shown without them first, then with them.
            val steam = game.fsName?.toIntOrNull()?.takeIf { game.platformSlug == LocalShelves.PC_SLUG }
                ?.let { appId -> flow<SteamDetails?> { emit(null); emit(steamStore.details(appId)) } }
                ?: flowOf(null)
            combine(
                combine(play, steam) { p, st -> p to st },
                // Reading and lookups are both kept per game key.
                combine(readingDao.gameTotals("game:${game.id}"), gameLookups.totals("game:${game.id}")) { r, l -> r to l },
                downloadQueue.downloads.map { it[game.id] },
                saveSync.status.map { it[game.id] },
                // Favourite, and how its text would be read (the cached catalog only, off the main thread).
                combine(favouriteDao.ids().map { game.id in it }, textSourceOf(game), lookOf(game), tuningOf(game)) { f, t, l, n -> Extras(f, t, l, n) },
            ) { (p, st), r, d, s, f ->
                GameDetails(
                    st?.let { focus.copy(game = it.applyTo(game)) } ?: focus,
                    p, r.first, d, s,
                    st?.let { GameArt(fanart = it.background, screenshots = it.screenshots) } ?: art,
                    f.favourite, r.second, f.source, f.look, f.tuning,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _notice = MutableStateFlow<String?>(null)
    /** A short message under the buttons, e.g. while the manual downloads. Clears itself. */
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var noticeJob: Job? = null

    private val manualFetcher = ManualFetcher(manuals, viewModelScope, failedLabel = "Couldn't fetch the manual")
    /** The manual being fetched for "Open manual", or that it couldn't be, for the button to show. */
    val manualFetch: StateFlow<ManualFetch?> = manualFetcher.state

    private val _manual = MutableStateFlow<File?>(null)
    /** The fetched manual the bottom screen is showing in [ManualViewer], or null. */
    val manual: StateFlow<File?> = _manual.asStateFlow()

    init {
        viewModelScope.launch { launcher.inFront.drop(1).collect { readerPinned.value = false } }
        // A message about one game means nothing once another is focused.
        viewModelScope.launch {
            focusedGame.focus.map { it?.game?.id }.drop(1).collect {
                _pickingLook.value = false
                manualFetcher.cancel()
                _manual.value = null
                noticeJob?.cancel()
                _notice.value = null
            }
        }
        // The manual open here is in use: a newer one from the server waits until it's closed.
        viewModelScope.launch { _manual.collect { manuals.use("details", it) } }
        // A manual open when a game starts stays closed after it, rather than coming back at page 1.
        viewModelScope.launch {
            mode.collect { if (it == CompanionMode.READER) _manual.value = null }
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

    /** Marks the focused game as a favourite, or unmarks it. */
    fun toggleFavourite() {
        val details = details.value ?: return
        val game = details.focus.game
        if (!Favourites.canMark(game)) return
        viewModelScope.launch {
            val marked = favouriteDao.toggle(game.id, System.currentTimeMillis())
            show(if (marked) "Added to favourites" else "Removed from favourites")
        }
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
        if (!manuals.has(game)) return
        // Shown on the button that was pressed; the reason goes to the notice below the buttons.
        manualFetcher.open(
            game,
            onFile = {
                _manual.value = it
                if (manuals.takeReplacedNote(game)) show(Manuals.REPLACED_NOTE)
            },
            onFailed = { show("Couldn't fetch the manual: ${it.message}") },
        )
    }

    /**
     * Closes the viewer showing [file]. [problem], when given, says why (the file wouldn't open);
     * the cached file is then deleted, so the next tap fetches it again. Nothing happens when
     * another manual has been opened since: a failure that reports late is about a viewer that
     * is already gone.
     */
    fun closeManual(file: File, problem: String? = null) {
        if (!_manual.compareAndSet(file, null)) return
        if (problem == null) return
        viewModelScope.launch(Dispatchers.IO) { file.delete() }
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

    private fun textSourceOf(game: GameEntity) =
        if (LocalShelves.isLocal(game)) {
            flowOf(null)
        } else {
            // The built-in player when it's on and has a core for the console, else the emulator app.
            combine(yomikataIds.idFor(game.id), playerSettings.enabled) { id, on -> id to (on && CoreCatalog.forPlatform(game.platformSlug) != null) }
                .map { (id, builtIn) ->
                    withContext(Dispatchers.IO) { TextSource.of(id, catalog::cachedEntry, { catalog.table(it) != null }, builtIn, catalog::cachedHasScript) }
                }
        }

    private data class Extras(val favourite: Boolean, val source: TextSource?, val look: GameLook?, val tuning: GameTuningNote?)

    /** The game's own step, when the built-in player is on, runs its console with a ladder, and the game has one. */
    private fun tuningOf(game: GameEntity) =
        if (LocalShelves.isLocal(game)) {
            flowOf(null)
        } else {
            // Read again as home comes back: the player keeps what it learned from its own process.
            combine(playerSettings.enabled, tuningStore.changes, launcher.inFront) { on, _, _ ->
                val ladder = CoreCatalog.forPlatform(game.platformSlug)?.let { app.genkan.core.TuningLadders.of(it.id) }
                if (!on || ladder == null) return@combine null
                val own = withContext(Dispatchers.IO) { tuningStore.override(game.id) } ?: return@combine null
                ladder.step(own.step)?.let { GameTuningNote(it.label, own.why.startsWith("learned")) }
            }
        }

    /** Forgets the focused game's own step: it starts at the catalog's or its core's again. */
    fun resetTuning() {
        val game = details.value?.focus?.game ?: return
        viewModelScope.launch(Dispatchers.IO) { tuningStore.clearOverride(game.id) }
    }

    private fun lookOf(game: GameEntity) =
        if (LocalShelves.isLocal(game) || CoreCatalog.forPlatform(game.platformSlug) == null) {
            flowOf(null)
        } else {
            combine(playerSettings.enabled, screenLooks.game(game.id), screenLooks.console(game.platformSlug)) { on, own, console ->
                if (on) GameLook(own, console) else null
            }
        }

    private val _pickingLook = MutableStateFlow(false)
    /** Whether the list of looks is open for the focused game. */
    val pickingLook: StateFlow<Boolean> = _pickingLook.asStateFlow()

    fun openLookPicker() {
        if (details.value?.look != null) _pickingLook.value = true
    }

    fun closeLookPicker() {
        _pickingLook.value = false
    }

    /** The game's own look ([look]), or its console's (null); the list closes. */
    fun pickLook(look: ScreenLook?) {
        val game = details.value?.focus?.game ?: return
        _pickingLook.value = false
        viewModelScope.launch { screenLooks.setGame(game.id, look) }
    }

    private fun artFor(game: GameEntity): GameArt {
        if (LocalShelves.isLocal(game) || session.currentAccount() == null) return GameArt()
        val versions = artLoaders.versions.value
        val url = { path: String? -> RomMArt.of(path)?.let { it.copy(version = versions[it.key] ?: 0) } }
        return GameArt(
            physical = url(game.physicalPath),
            logo = url(game.logoPath),
            fanart = url(game.fanartPath),
            screenshots = game.screenshotPathList.mapNotNull(url),
            hasManual = game.manualPath != null,
        )
    }

    companion object {
        private const val NOTICE_MS = 4_000L
    }
}
