package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.art.ArtLoaders
import app.genkan.art.RomMArt
import app.genkan.art.ShelfPrefetch
import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadQueue
import app.genkan.download.DownloadState
import app.genkan.input.PadKey
import app.genkan.launch.GameLauncher
import app.genkan.launch.GameNative
import app.genkan.launch.EmulatorCatalog
import app.genkan.launch.EmulatorDef
import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import app.genkan.launch.HookKit
import app.genkan.launch.LocalShelves
import app.genkan.launch.LaunchResult
import app.genkan.play.PlaySessionDao
import app.genkan.play.SessionTracker
import app.genkan.reader.NowPlaying
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import android.animation.ValueAnimator
import android.os.SystemClock
import app.genkan.home.LauncherPresence
import app.genkan.launch.LaunchPlan
import app.genkan.library.ArtworkWarmer
import app.genkan.library.ContinuePlaying
import app.genkan.library.FavouriteDao
import app.genkan.library.Favourites
import app.genkan.library.GameCase
import app.genkan.library.InsertTimeline
import app.genkan.library.MediaKind
import kotlinx.coroutines.flow.first
import app.genkan.library.FocusedGame
import app.genkan.saves.SaveSync
import app.genkan.azahar.AzaharText
import app.genkan.ppsspp.PpssppText
import kotlinx.coroutines.withTimeoutOrNull
import app.genkan.library.GameEntity
import app.genkan.library.LibraryDao
import app.genkan.library.LibraryFocus
import app.genkan.library.FocusKeeper
import app.genkan.library.LibraryNavigator
import app.genkan.library.LibraryOrder
import app.genkan.library.OrderPanel
import app.genkan.library.LibraryPane
import app.genkan.library.LibrarySync
import app.genkan.library.LibraryView
import app.genkan.library.LibraryViewSettings
import app.genkan.library.PlatformEntity
import app.genkan.library.SyncStatus
import app.genkan.romm.RomMSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class LibraryEvent {
    /** Downloads need "All files access" so emulators can read the shared ROMs folder. */
    data object NeedStorageAccess : LibraryEvent()
    /** Ask once, so the download progress notification is visible. */
    data object NeedNotifications : LibraryEvent()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    dao: LibraryDao,
    playSessions: PlaySessionDao,
    private val favouriteDao: FavouriteDao,
    private val session: RomMSession,
    private val librarySync: LibrarySync,
    private val downloadQueue: DownloadQueue,
    private val gameLauncher: GameLauncher,
    private val localShelves: LocalShelves,
    private val hookKit: HookKit,
    private val sessionTracker: SessionTracker,
    private val nowPlaying: NowPlaying,
    private val focusedGame: FocusedGame,
    private val saveSync: SaveSync,
    private val ppssppText: PpssppText,
    private val azaharText: AzaharText,
    private val artworkWarmer: ArtworkWarmer,
    private val viewSettings: LibraryViewSettings,
    private val launcherPresence: LauncherPresence,
    private val artLoaders: ArtLoaders,
    private val shelfPrefetch: ShelfPrefetch,
) : ViewModel() {

    private val local = MutableStateFlow(LocalShelves.Shelves(emptyList(), emptyMap()))

    val downloads: StateFlow<Map<Long, DownloadEntity>> = downloadQueue.downloads

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** A short message under the title, e.g. the delete confirmation. Clears itself. */
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var noticeJob: Job? = null
    private var deleteArmedFor: Long? = null

    /** Set by the activity: whether Genkan may write to shared storage. */
    var hasStorageAccess: () -> Boolean = { true }

    private val _focus = MutableStateFlow(LibraryFocus())
    val focus: StateFlow<LibraryFocus> = _focus.asStateFlow()

    private val _insert = MutableStateFlow<InsertState?>(null)
    /** The insert animation playing over the library while a game starts, or null. */
    val insert: StateFlow<InsertState?> = _insert.asStateFlow()

    /** The game being started; one at a time. */
    private var launchJob: Job? = null
    /** When the launch began, on the elapsed-realtime clock, and when (since then) it was skipped. */
    private var launchStartedAt = 0L
    private val skippedAt = MutableStateFlow<Long?>(null)

    /**
     * Skips the insert animation when one is playing (see [InsertKey] for which keys may). True
     * when it was.
     */
    fun skipInsert(): Boolean {
        val state = _insert.value ?: return false
        if (!state.skipped) {
            skippedAt.value = SystemClock.elapsedRealtime() - launchStartedAt
            _insert.value = state.copy(skipped = true)
        }
        return true
    }

    /** The shelf or the grid, as last left; the shelf until the stored choice has been read. */
    val view: StateFlow<LibraryView> = viewSettings.view.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryView.DEFAULT)

    /** The Full box setting: whether R3 can turn a box, for the key and the hint. */
    val fullBox: StateFlow<Boolean> = viewSettings.fullBox.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _turnedId = MutableStateFlow<Long?>(null)
    /** The game whose full box is turned to its back (R3), if any; see [BoxTurn]. */
    val turnedId: StateFlow<Long?> = _turnedId.asStateFlow()


    /** Switches between the shelf and the grid, and remembers the choice. */
    fun toggleView() {
        val next = view.value.toggled()
        viewModelScope.launch { viewSettings.setView(next) }
    }

    /** The games on the "Continue playing" shelf, RomM's and PC games, most recently played first. */
    private val recent: StateFlow<List<GameEntity>> =
        combine(
            playSessions.recentPlays(ContinuePlaying.MIN_PLAY_MS),
            playSessions.recentlyPlayed(ContinuePlaying.MIN_PLAY_MS, ContinuePlaying.MAX_GAMES),
            local,
        ) { plays, romm, shelves -> ContinuePlaying.merge(plays, romm, shelves.games[LocalShelves.PC_ID].orEmpty()) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The games on the "Favourites" shelf, by title. */
    private val favourites: StateFlow<List<GameEntity>> =
        favouriteDao.games().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Every game marked as a favourite, for what R2 and the hints say. */
    val favouriteIds: StateFlow<Set<Long>> =
        favouriteDao.ids().map { it.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Whether the player has moved the focus yet; until then the library opens on "Continue playing". */
    private var moved = false

    /**
     * "Continue playing" first once a game has been played, then "Favourites" once one is marked,
     * then PC games (the visual novels live there), then RomM's platforms, then apps.
     */
    val platforms: StateFlow<List<PlatformEntity>> =
        combine(dao.platforms(), local, recent, favourites) { romm, shelves, played, marked ->
            listOfNotNull(
                ContinuePlaying.platform(played.size).takeIf { played.isNotEmpty() },
                Favourites.platform(marked.size).takeIf { marked.isNotEmpty() },
            ) +
                shelves.platforms.filter { it.id == LocalShelves.PC_ID } + romm + shelves.platforms.filter { it.id == LocalShelves.APPS_ID }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The sort and filter for consoles' games, as last left. */
    val order: StateFlow<LibraryOrder> = viewSettings.order.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryOrder())

    /** The games on this device, for "downloaded only". */
    private val installed = downloadQueue.downloads
        .map { rows -> rows.filterValues { it.state == DownloadState.INSTALLED }.keys }
        .distinctUntilChanged()

    /** Each game's latest play here, for "last played". */
    private val lastPlayed = playSessions.lastPlayedByGame().map { rows -> rows.associate { it.romId to it.lastPlayed } }

    private val _orderPanel = MutableStateFlow<Int?>(null)
    /** The "Sort & filter" panel's focused row while it is open (0 the sort, 1 the filter), else null. */
    val orderPanel: StateFlow<Int?> = _orderPanel.asStateFlow()

    /** The focus the last key moved to, and whether a held key's repeat moved it there. */
    private data class KeyStep(val focus: LibraryFocus?, val repeat: Boolean)

    /**
     * The last key's step. A focus counts as a repeat's only while it is the one this step led to:
     * a tap, a reload of the shelf or a new order moving it is not a key's, and shows at once.
     */
    private val keyStep = MutableStateFlow(KeyStep(null, repeat = false))

    /** The bottom screen's next focus, and whether a held key's repeat moved it. */
    private data class FollowedFocus(val focus: FocusedGame.Focus?, val repeat: Boolean)

    /** The game the focus stays on while a new order rearranges a console's shelf. */
    private val keeper = FocusKeeper()

    /**
     * The selected shelf's games, with the shelf they are of. Switching shelves keeps the last
     * shelf's list until the new one's first query returns; the id says which one this is.
     */
    val shelf: StateFlow<ShelfGames> =
        combine(platforms, _focus.map { it.platformIndex }.distinctUntilChanged()) { list, index -> list.getOrNull(index) }
            .map { it?.id }
            .distinctUntilChanged()
            .flatMapLatest { id ->
                when {
                    id == null -> flowOf(emptyList())
                    id == ContinuePlaying.ID -> recent
                    id == Favourites.ID -> favourites
                    id < 0 -> local.map { it.games[id].orEmpty() }
                    // A console's games in the chosen order; the other shelves keep their own.
                    else -> combine(dao.games(id), order, installed, lastPlayed) { list, o, here, played -> o.arrange(list, here, played) }
                }.map { ShelfGames(id, it) }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ShelfGames(null, emptyList()))

    val games: StateFlow<List<GameEntity>> = shelf.map { it.games }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { sessionTracker.ended.collect(::onPlayEnded) }
        // A new sort or filter moves the games: the focus stays on the one it was on, if shown.
        viewModelScope.launch {
            games.collect { list ->
                val index = keeper.place(list) ?: return@collect
                _focus.update { it.copy(gameIndex = index) }
            }
        }
        // The strip changes under the focus: "Continue playing" appears, a local shelf loads.
        viewModelScope.launch {
            var before = emptyList<PlatformEntity>()
            platforms.collect { after ->
                _focus.update { ContinuePlaying.refocus(it, before, after, moved) }
                before = after
            }
        }
        // A play moves its game to the front of "Continue playing", and marking or unmarking a
        // favourite changes "Favourites": the focus stays on its game, or the nearest place.
        for ((shelf, list) in listOf(ContinuePlaying.ID to recent, Favourites.ID to favourites)) {
            viewModelScope.launch {
                var before = emptyList<GameEntity>()
                list.collect { after ->
                    if (platforms.value.getOrNull(_focus.value.platformIndex)?.id == shelf) {
                        _focus.update { it.copy(gameIndex = ContinuePlaying.keepGame(it.gameIndex, before, after)) }
                    }
                    before = after
                }
            }
        }
        refreshLocal()
        // The bottom screen shows the focused game while the launcher is in front. A step after a
        // rest shows at once; steps that follow it closely (a held D-pad) wait for the focus to
        // pause: both screens draw on this one main thread, and redrawing the details (their
        // queries, cover and summary) on every step of a held D-pad costs the shelf frames.
        viewModelScope.launch {
            combine(_focus, keyStep, games, platforms) { focus, step, list, shelves ->
                val game = list.getOrNull(focus.gameIndex) ?: list.firstOrNull()
                val shown = game?.let { FocusedGame.Focus(it, ContinuePlaying.consoleOf(it, shelves, shelves.getOrNull(focus.platformIndex)), coverModel(it)) }
                // A held D-pad's repeat never shows at once: its first comes 400 ms after the
                // press, and rebuilding the details then costs the shelf frames as it starts to scroll.
                FollowedFocus(shown, repeat = step.focus == focus && step.repeat)
            }.distinctUntilChanged { a, b -> a.focus == b.focus }
                .restThrottle(DETAILS_FOLLOW_MS, atOnce = { !it.repeat })
                .collect { focusedGame.set(it.focus) }
        }
        // The bottom screen's Play / Download button does what A does on that game.
        viewModelScope.launch {
            focusedGame.playRequests.collect { id -> games.value.firstOrNull { it.id == id }?.let(::primaryAction) }
        }
        // A turned box turns back when the focus moves (or the shelf changes).
        viewModelScope.launch {
            _focus.map { Triple(it.platformIndex, it.gameIndex, it.pane) }.distinctUntilChanged().collect { _turnedId.value = BoxTurn.onFocusMoved() }
        }
        // The artwork around the focused game: ahead of it while it moves, all the shelf shows
        // once it rests.
        viewModelScope.launch {
            // The Full box setting too: switching it (or the store's first read) warms again.
            combine(_focus.map { it.gameIndex }.distinctUntilChanged(), games, viewSettings.fullBox) { index, list, box -> Triple(list, index, box) }
                .collectLatest { (list, index, box) ->
                    val at = index.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                    // R3 can turn the focused full box to its back at any time, so its back is warmed
                    // too, once the focus rests: not for every game a held direction passes.
                    artworkWarmer.warmAround(list, at, moving = true, fullBox = box)
                    delay(WARM_REST_MS)
                    artworkWarmer.warmAround(list, at, moving = false, fullBox = box, showsBack = box)
                }
        }
        // The whole opened shelf's art, fetched ahead in the background (and checked once a day):
        // started again for each shelf, from where the focus is.
        viewModelScope.launch {
            combine(games, viewSettings.fullBox) { list, box -> list to box }
                .distinctUntilChanged()
                .collectLatest { (list, box) -> if (list.isNotEmpty()) shelfPrefetch.start(list, _focus.value.gameIndex, box) }
        }
    }

    override fun onCleared() {
        shelfPrefetch.stop()
        super.onCleared()
    }

    /** Re-reads GameNative's export and the app list; cheap, so it runs on every resume. */
    fun refreshLocal() {
        viewModelScope.launch { local.value = localShelves.load() }
    }

    val syncStatus: StateFlow<SyncStatus> = librarySync.status

    /** Set by the grid from its measured width, so up/down move by whole rows. */
    var columns: Int = 1

    /** What the card shows: an app's icon, a Steam cover's URL, or a RomM cover ([RomMArt]). */
    fun coverModel(game: GameEntity): Any? = when {
        game.platformSlug == LocalShelves.APPS_SLUG -> localShelves.appIcon(game)
        LocalShelves.isLocal(game) -> game.coverPath
        else -> artModel(game.coverPath)
    }

    /**
     * A RomM asset path (a cover, spine, logo or disc cut-out) as [RomMArt], at the version the
     * daily check has reached for it, or null without one or while signed out.
     */
    fun artModel(path: String?): Any? = session.currentAccount()?.let {
        RomMArt.of(path)?.let { art -> art.copy(version = artLoaders.versions.value[art.key] ?: 0) }
    }

    /** Raised when a file was found replaced on the server: the screens make their art again. */
    val artVersions: StateFlow<Map<String, Int>> = artLoaders.versions

    /**
     * Handles a gamepad key; [repeatCount] is the key event's, so a held D-pad can speed up along
     * the shelf. Returns false for keys the library doesn't use.
     */
    fun onKey(key: PadKey, repeatCount: Int = 0): Boolean {
        // The activity decides what a key does to the insert animation; nothing else moves under it.
        if (_insert.value != null) return true
        // The "Sort & filter" panel has the pad while it is open.
        _orderPanel.value?.let { row -> return onOrderPanelKey(row, key, repeatCount) }
        if (key == PadKey.L2) {
            if (repeatCount == 0) _orderPanel.value = 0
            return true
        }
        if (key == PadKey.Y) {
            sync(); return true
        }
        // The right stick's click turns the focused game's full box round, to its back and again.
        if (key == PadKey.R3) {
            val game = games.value.getOrNull(_focus.value.gameIndex).takeIf { _focus.value.pane == LibraryPane.GAMES }
            if (repeatCount == 0) _turnedId.value = BoxTurn.onR3(_turnedId.value, game, fullBox.value)
            return true
        }
        // The left stick's click flips "Downloaded only", the panel's filter, without opening it.
        if (key == PadKey.L3) {
            if (repeatCount == 0) {
                val on = !order.value.downloadedOnly
                setOrder(order.value.copy(downloadedOnly = on))
                // A click is easy to make by accident while pushing the stick: say what happened.
                show(if (on) "Downloaded only · L3: all games" else "All games")
            }
            return true
        }
        if (key == PadKey.SELECT) {
            // Select closes a lingering emulator when one is offered; otherwise it switches view.
            if (lingering != null) closeLingering() else if (repeatCount == 0) toggleView()
            return true
        }
        val focusedGame = games.value.getOrNull(_focus.value.gameIndex).takeIf { _focus.value.pane == LibraryPane.GAMES }
        if (focusedGame != null && key == PadKey.A) {
            primaryAction(focusedGame); return true
        }
        if (focusedGame != null && key == PadKey.X) {
            deleteAction(focusedGame); return true
        }
        if (key == PadKey.R2) {
            // Only on a RomM game; on the console strip R2 does nothing, and the hints don't offer it.
            val game = focusedGame?.takeIf(Favourites::canMark) ?: return false
            toggleFavourite(game)
            return true
        }
        val next = LibraryNavigator.onKey(_focus.value, key, platforms.value.size, games.value.size, columns, view.value, repeatCount)
            ?: return false
        moved = true
        // Said with the focus it led to, so the details can tell a held D-pad's repeat from a press.
        keyStep.value = KeyStep(next, repeat = repeatCount > 0)
        _focus.value = next
        return true
    }

    fun selectPlatform(index: Int) {
        moved = true
        keeper.drop()
        _focus.update {
            if (it.platformIndex == index) it.copy(pane = LibraryPane.PLATFORMS)
            else LibraryFocus(pane = LibraryPane.PLATFORMS, platformIndex = index)
        }
    }

    /** A tap on a game focuses it; a tap on the focused game acts like A. */
    fun selectGame(index: Int) {
        moved = true
        keeper.drop()
        val wasFocused = _focus.value.pane == LibraryPane.GAMES && _focus.value.gameIndex == index
        _focus.update { it.copy(pane = LibraryPane.GAMES, gameIndex = index) }
        if (wasFocused) games.value.getOrNull(index)?.let(::primaryAction)
    }

    private fun primaryAction(game: GameEntity) {
        if (LocalShelves.isLocal(game)) {
            // PC games read their text through the hook kit on D:; refresh it while the game loads.
            if (game.platformSlug == LocalShelves.PC_SLUG && hasStorageAccess()) viewModelScope.launch { hookKit.install() }
            if (localShelves.launch(game)) {
                // A PC game's play is timed like an emulator's, under its local id, for "Continue playing".
                if (game.platformSlug == LocalShelves.PC_SLUG) viewModelScope.launch { sessionTracker.begin(game.id, GameNative.PACKAGE) }
                nowPlaying.started(game.id, game.name, game.platformSlug)
                show("Opening ${game.name}…")
            } else {
                show("Couldn't open ${game.name}")
            }
            return
        }
        when (downloads.value[game.id]?.state) {
            DownloadState.QUEUED, DownloadState.RUNNING -> {
                downloadQueue.cancel(game.id); show("Download cancelled")
            }
            DownloadState.INSTALLED -> launch(game)
            null, DownloadState.FAILED -> {
                if (!hasStorageAccess()) {
                    _events.tryEmit(LibraryEvent.NeedStorageAccess)
                    show("Allow \"All files access\" for Genkan, then press A again")
                    return
                }
                _events.tryEmit(LibraryEvent.NeedNotifications)
                downloadQueue.enqueue(game)
            }
        }
    }

    /** Name of the emulator A would start for [platformSlug], or null when none is installed. */
    fun emulatorFor(platformSlug: String): String? = gameLauncher.installedEmulatorFor(platformSlug)?.first?.displayName

    /**
     * Starts an installed [game]. The one path for A and the bottom screen's Play button.
     *
     * The insert animation plays while the game gets ready (its save pulled from RomM, the emulator
     * chosen, firmware fetched), and the game starts as [InsertTimeline] says: when both are done
     * for the built-in player, and a little before the animation ends for an emulator app, so its
     * window lands as the animation finishes. The bottom screen goes to the reader as it begins.
     */
    private fun launch(game: GameEntity) {
        val download = downloads.value[game.id] ?: return
        if (launchJob?.isActive == true) return
        launchJob = viewModelScope.launch {
            val animationMs = InsertTimeline.animationMs(viewSettings.insertAnimation.first(), ValueAnimator.getDurationScale())
            launchStartedAt = SystemClock.elapsedRealtime()
            skippedAt.value = null
            focusedGame.setLaunching(game.id)
            if (animationMs > 0) {
                _insert.value = InsertState(
                    gameId = game.id,
                    title = game.name,
                    kind = MediaKind.forSlug(game.platformSlug),
                    spec = GameCase.specFor(game.platformSlug),
                    physical = artModel(game.physicalPath),
                    logo = artModel(game.logoPath),
                    durationMs = animationMs,
                )
            }
            var launched = false
            try {
                // Pull a newer save from RomM first; a slow or absent server never holds the game up.
                show("Checking ${game.name}'s save…")
                withTimeoutOrNull(SAVE_SYNC_TIMEOUT_MS) {
                    saveSync.sync(game.id)
                    saveSync.beforeLaunch(game.id, game.platformSlug)
                    if (game.platformSlug == "psp") ppssppText.beforeLaunch()
                    if (game.platformSlug == "3ds") azaharText.beforeLaunch()
                }
                val result = when (val plan = gameLauncher.prepare(game, download)) {
                    is LaunchPlan.Unavailable -> plan.result
                    is LaunchPlan.Ready -> {
                        val readyAt = sinceLaunch()
                        val timeline = InsertTimeline.forLaunch(animationMs, plan.builtIn)
                        waitUntil { timeline.launchAt(readyAt, skippedAt.value) }
                        plan.start().also { result ->
                            if (result is LaunchResult.Launched) {
                                waitUntil { timeline.cutAt(readyAt, skippedAt.value) }
                                // The last frame (a disc still spinning) stays up until the game's
                                // window has the screen, so the shelf never shows in between.
                                if (_insert.value != null) {
                                    withTimeoutOrNull(OVERLAY_HOLD_MS) { launcherPresence.inFront.first { !it } }
                                }
                            }
                        }
                    }
                }
                when (result) {
                    is LaunchResult.Launched -> {
                        launched = true
                        sessionTracker.begin(game.id, result.emulator.packageName)
                        nowPlaying.started(game.id, game.name, game.platformSlug)
                        show("Starting ${game.name} in ${result.emulator.displayName}…" + (result.note?.let { " ($it)" } ?: ""))
                    }
                    is LaunchResult.NoEmulator -> show(
                        if (result.suggestions.isEmpty()) "Genkan can't launch this platform yet"
                        else "Install ${result.suggestions.map { it.displayName.substringBefore(" (") }.distinct().joinToString(" or ")} to play this"
                    )
                    is LaunchResult.Failed -> show(result.message)
                }
            } finally {
                _insert.value = null
                if (!launched) focusedGame.setLaunching(null)
            }
            // Nothing started: A works again straight away.
            if (!launched) return@launch
            // The bottom screen stays on the reader until the game has the top screen; then the
            // usual rule (details only while the launcher is in front) takes over.
            withTimeoutOrNull(LAUNCHING_HOLD_MS) { launcherPresence.inFront.first { !it } }
            focusedGame.setLaunching(null)
        }
    }

    private fun sinceLaunch(): Long = SystemClock.elapsedRealtime() - launchStartedAt

    /** Suspends until [due] (ms since the launch began); a skip can bring it earlier, so it is re-read. */
    private suspend fun waitUntil(due: () -> Long) {
        while (true) {
            val wait = due() - sinceLaunch()
            if (wait <= 0) return
            val seen = skippedAt.value
            withTimeoutOrNull(wait) { skippedAt.first { it != seen } }
        }
    }

    /**
     * Up and down pick a row (and may repeat: they stop at the ends); left, right and A change it
     * once per press, so a held button doesn't race through the sorts; B or L2 closes; every
     * other key waits.
     */
    private fun onOrderPanelKey(row: Int, key: PadKey, repeatCount: Int): Boolean {
        val fresh = repeatCount == 0
        when (key) {
            PadKey.UP -> _orderPanel.value = (row - 1).coerceAtLeast(0)
            PadKey.DOWN -> _orderPanel.value = (row + 1).coerceAtMost(OrderPanel.ROWS - 1)
            PadKey.LEFT -> if (fresh) setOrder(OrderPanel.change(order.value, row, -1))
            PadKey.RIGHT, PadKey.A -> if (fresh) setOrder(OrderPanel.change(order.value, row, 1))
            PadKey.B, PadKey.L2 -> if (fresh) closeOrderPanel()
            else -> Unit
        }
        return true
    }

    /** Opens or closes the "Sort & filter" panel (a tap on its Close, or L2). */
    fun toggleOrderPanel() {
        if (_orderPanel.value == null) _orderPanel.value = 0 else closeOrderPanel()
    }

    private fun closeOrderPanel() {
        _orderPanel.value = null
        keeper.drop()
    }

    fun setOrder(next: LibraryOrder) {
        // Only a console's shelf is rearranged; the others keep their own order.
        val shelf = platforms.value.getOrNull(_focus.value.platformIndex)?.id ?: 0
        keeper.keep(games.value.getOrNull(_focus.value.gameIndex)?.id?.takeIf { shelf > 0 })
        viewModelScope.launch { viewSettings.setOrder(next) }
    }

    /** Marks [game] as a favourite, or unmarks it. */
    private fun toggleFavourite(game: GameEntity) {
        viewModelScope.launch {
            val marked = favouriteDao.toggle(game.id, System.currentTimeMillis())
            show(if (marked) "Added ${game.name} to favourites" else "Removed ${game.name} from favourites")
        }
    }

    private fun deleteAction(game: GameEntity) {
        if (downloads.value[game.id]?.state != DownloadState.INSTALLED) return
        if (deleteArmedFor == game.id) {
            deleteArmedFor = null
            downloadQueue.delete(game.id)
            show("Deleted ${game.name}")
        } else {
            deleteArmedFor = game.id
            show("Press X again to delete ${game.name} from this device")
        }
    }

    /**
     * An emulator that draws on both screens (Azahar) keeps its bottom-screen window after Home,
     * over the companion, until its process ends. Genkan can't close another app's window, and
     * ending the process on its own could lose unsaved play, so it asks.
     */
    private var lingering: EmulatorDef? = null

    private fun onPlayEnded(ended: SessionTracker.Ended) {
        val emulator = EmulatorCatalog.emulators.firstOrNull { it.packageName == ended.emulatorPackage } ?: return
        if (!emulator.drawsBothScreens) return
        lingering = emulator
        noticeJob?.cancel()
        _notice.value = "Select: close ${emulator.displayName} (it still has the bottom screen)"
    }

    private fun closeLingering() {
        val emulator = lingering ?: return
        lingering = null
        // Only a process in the background can be ended this way; a running game is never touched.
        context.getSystemService(ActivityManager::class.java).killBackgroundProcesses(emulator.packageName)
        show("Closed ${emulator.displayName}")
    }

    private fun show(message: String) {
        lingering = null
        noticeJob?.cancel()
        _notice.value = message
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _notice.value = null
            deleteArmedFor = null
        }
    }

    private companion object {
        const val NOTICE_MS = 4_000L
        /** How long the focus rests before the artwork warmer stops looking ahead. */
        const val WARM_REST_MS = 250L
        const val SAVE_SYNC_TIMEOUT_MS = 15_000L
        /** The longest the bottom screen waits on the reader for a started game to take the top screen. */
        const val LAUNCHING_HOLD_MS = 5_000L
        /** The longest the animation's last frame waits for a started game's window. */
        const val OVERLAY_HOLD_MS = 3_000L
    }

    fun sync() {
        refreshLocal()
        viewModelScope.launch { librarySync.sync() }
    }
}

/** A shelf's games ([games]) and the shelf they are of ([platformId]; null before there is one). */
data class ShelfGames(val platformId: Long?, val games: List<GameEntity>)
