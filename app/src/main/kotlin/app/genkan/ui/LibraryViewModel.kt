package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadQueue
import app.genkan.download.DownloadState
import app.genkan.input.PadKey
import app.genkan.launch.GameLauncher
import app.genkan.launch.LocalShelves
import app.genkan.launch.LaunchResult
import app.genkan.play.SessionTracker
import app.genkan.reader.NowPlaying
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import app.genkan.library.GameEntity
import app.genkan.library.LibraryDao
import app.genkan.library.LibraryFocus
import app.genkan.library.LibraryNavigator
import app.genkan.library.LibraryPane
import app.genkan.library.LibrarySync
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
    dao: LibraryDao,
    private val session: RomMSession,
    private val librarySync: LibrarySync,
    private val downloadQueue: DownloadQueue,
    private val gameLauncher: GameLauncher,
    private val localShelves: LocalShelves,
    private val sessionTracker: SessionTracker,
    private val nowPlaying: NowPlaying,
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
        refreshLocal()
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

    fun onKey(key: PadKey): Boolean {
        if (key == PadKey.Y) {
            sync(); return true
        }
        val focusedGame = games.value.getOrNull(_focus.value.gameIndex).takeIf { _focus.value.pane == LibraryPane.GAMES }
        if (focusedGame != null && key == PadKey.A) {
            primaryAction(focusedGame); return true
        }
        if (focusedGame != null && key == PadKey.X) {
            deleteAction(focusedGame); return true
        }
        val next = LibraryNavigator.onKey(_focus.value, key, platforms.value.size, games.value.size, columns)
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

    private fun show(message: String) {
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
    }

    fun sync() {
        refreshLocal()
        viewModelScope.launch { librarySync.sync() }
    }
}
