package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadQueue
import app.genkan.download.DownloadState
import app.genkan.input.PadKey
import app.genkan.launch.GameLauncher
import app.genkan.launch.EmulatorCatalog
import app.genkan.launch.EmulatorDef
import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import app.genkan.launch.HookKit
import app.genkan.launch.LocalShelves
import app.genkan.launch.LaunchResult
import app.genkan.play.SessionTracker
import app.genkan.reader.NowPlaying
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import app.genkan.library.ArtworkWarmer
import app.genkan.library.FocusedGame
import app.genkan.saves.SaveSync
import app.genkan.azahar.AzaharText
import app.genkan.ppsspp.PpssppText
import kotlinx.coroutines.withTimeoutOrNull
import app.genkan.library.GameEntity
import app.genkan.library.LibraryDao
import app.genkan.library.LibraryFocus
import app.genkan.library.LibraryNavigator
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

    /** The shelf or the grid, as last left; the shelf until the stored choice has been read. */
    val view: StateFlow<LibraryView> = viewSettings.view.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryView.DEFAULT)

    /** Switches between the shelf and the grid, and remembers the choice. */
    fun toggleView() {
        val next = view.value.toggled()
        viewModelScope.launch { viewSettings.setView(next) }
    }

    /** PC games first (the visual novels live there), then RomM's platforms, then apps. */
    val platforms: StateFlow<List<PlatformEntity>> =
        combine(dao.platforms(), local) { romm, shelves ->
            shelves.platforms.filter { it.id == LocalShelves.PC_ID } + romm + shelves.platforms.filter { it.id == LocalShelves.APPS_ID }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val games: StateFlow<List<GameEntity>> =
        combine(platforms, _focus.map { it.platformIndex }.distinctUntilChanged()) { list, index -> list.getOrNull(index) }
            .map { it?.id }
            .distinctUntilChanged()
            .flatMapLatest { id ->
                when {
                    id == null -> flowOf(emptyList())
                    id < 0 -> local.map { it.games[id].orEmpty() }
                    else -> dao.games(id)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { sessionTracker.ended.collect(::onPlayEnded) }
        refreshLocal()
        // The bottom screen shows the focused game while the launcher is in front.
        viewModelScope.launch {
            combine(_focus, games, platforms) { focus, list, shelves ->
                val game = list.getOrNull(focus.gameIndex) ?: list.firstOrNull()
                game?.let { FocusedGame.Focus(it, shelves.getOrNull(focus.platformIndex)?.name.orEmpty(), coverModel(it)) }
            }.distinctUntilChanged().collect(focusedGame::set)
        }
        // The artwork of the games around the focused one, so the next few are already here.
        viewModelScope.launch {
            combine(_focus.map { it.gameIndex }.distinctUntilChanged(), games) { index, list -> list to index }
                .collect { (list, index) -> artworkWarmer.warmAround(list, index.coerceIn(0, (list.size - 1).coerceAtLeast(0))) }
        }
    }

    /** Re-reads GameNative's export and the app list; cheap, so it runs on every resume. */
    fun refreshLocal() {
        viewModelScope.launch { local.value = localShelves.load() }
    }

    val syncStatus: StateFlow<SyncStatus> = librarySync.status

    /** Set by the grid from its measured width, so up/down move by whole rows. */
    var columns: Int = 1

    /** What the card shows: an app's icon, or a cover URL (RomM asset or Steam art). */
    fun coverModel(game: GameEntity): Any? = when {
        game.platformSlug == LocalShelves.APPS_SLUG -> localShelves.appIcon(game)
        LocalShelves.isLocal(game) -> game.coverPath
        else -> session.currentAccount()?.let { app.genkan.romm.RomMApiFactory.assetUrl(it.baseUrl, game.coverPath) }
    }

    /** A RomM asset path (a large cover, spine, logo or disc cut-out) as a URL, or null without one. */
    fun artModel(path: String?): Any? =
        path?.let { p -> session.currentAccount()?.let { app.genkan.romm.RomMApiFactory.assetUrl(it.baseUrl, p) } }

    /**
     * Handles a gamepad key; [repeatCount] is the key event's, so a held D-pad can speed up along
     * the shelf. Returns false for keys the library doesn't use.
     */
    fun onKey(key: PadKey, repeatCount: Int = 0): Boolean {
        if (key == PadKey.Y) {
            sync(); return true
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
        val next = LibraryNavigator.onKey(_focus.value, key, platforms.value.size, games.value.size, columns, view.value, repeatCount)
            ?: return false
        _focus.value = next
        return true
    }

    fun selectPlatform(index: Int) = _focus.update {
        if (it.platformIndex == index) it.copy(pane = LibraryPane.PLATFORMS)
        else LibraryFocus(pane = LibraryPane.PLATFORMS, platformIndex = index)
    }

    /** A tap on a game focuses it; a tap on the focused game acts like A. */
    fun selectGame(index: Int) {
        val wasFocused = _focus.value.pane == LibraryPane.GAMES && _focus.value.gameIndex == index
        _focus.update { it.copy(pane = LibraryPane.GAMES, gameIndex = index) }
        if (wasFocused) games.value.getOrNull(index)?.let(::primaryAction)
    }

    private fun primaryAction(game: GameEntity) {
        if (LocalShelves.isLocal(game)) {
            // PC games read their text through the hook kit on D:; refresh it while the game loads.
            if (game.platformSlug == LocalShelves.PC_SLUG && hasStorageAccess()) viewModelScope.launch { hookKit.install() }
            if (localShelves.launch(game)) {
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

    private fun launch(game: GameEntity) {
        val download = downloads.value[game.id] ?: return
        viewModelScope.launch {
            // Pull a newer save from RomM first; a slow or absent server never holds the game up.
            show("Checking ${game.name}'s save…")
            withTimeoutOrNull(SAVE_SYNC_TIMEOUT_MS) {
                saveSync.sync(game.id)
                saveSync.beforeLaunch(game.id, game.platformSlug)
                if (game.platformSlug == "psp") ppssppText.beforeLaunch()
                if (game.platformSlug == "3ds") azaharText.beforeLaunch()
            }
            when (val result = gameLauncher.launch(game, download)) {
                is LaunchResult.Launched -> {
                    sessionTracker.begin(game.id, result.emulator.packageName)
                    nowPlaying.started(game.id, game.name, game.platformSlug)
                    show("Starting ${game.name} in ${result.emulator.displayName}…")
                }
                is LaunchResult.NoEmulator -> show(
                    if (result.suggestions.isEmpty()) "Genkan can't launch this platform yet"
                    else "Install ${result.suggestions.map { it.displayName.substringBefore(" (") }.distinct().joinToString(" or ")} to play this"
                )
                is LaunchResult.Failed -> show(result.message)
            }
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
        const val SAVE_SYNC_TIMEOUT_MS = 15_000L
    }

    fun sync() {
        refreshLocal()
        viewModelScope.launch { librarySync.sync() }
    }
}
