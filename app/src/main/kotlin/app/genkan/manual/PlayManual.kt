package app.genkan.manual

import app.genkan.library.GameEntity
import app.genkan.library.LibraryDao
import app.genkan.reader.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The manual of the game being played, on the bottom screen over the reader: the reader's
 * Manual chip and the built-in player's stick click open it, its Back to reader (or Back) closes
 * it. The page it was left at is kept for the next time, until another game starts.
 */
@Singleton
class PlayManual internal constructor(
    nowPlaying: NowPlaying,
    private val library: LibraryDao,
    private val manuals: ManualSource,
    /** Where it follows the game being played: the app's lasts as long as the app; a test's ends with the test. */
    private val scope: CoroutineScope,
) {
    @Inject constructor(nowPlaying: NowPlaying, library: LibraryDao, manuals: ManualSource) :
        this(nowPlaying, library, manuals, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    /** The game being played, when it has a manual on the server. */
    val game: StateFlow<GameEntity?> = nowPlaying.game
        .map { playing -> playing.rommRomId?.let { library.game(it) }?.takeIf(manuals::has) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _open = MutableStateFlow<File?>(null)
    /** The manual on the bottom screen now, or null. */
    val open: StateFlow<File?> = _open.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** While the manual is fetched, or why it couldn't be: for the reader to say. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** The page each manual was left at (the first one on screen), by file. */
    private val pages = HashMap<String, Int>()
    private var fetching: Job? = null

    init {
        // Another game: its own manual, from its first page.
        scope.launch {
            nowPlaying.game.map { it.key }.distinctUntilChanged().drop(1).collect {
                hide()
                pages.clear()
            }
        }
    }

    /** Opens the manual (fetched first when it isn't cached); nothing when the game has none. */
    fun show() {
        val game = game.value ?: return
        if (_open.value != null || fetching?.isActive == true) return
        fetching = scope.launch {
            if (manuals.cached(game) == null) _notice.value = "Fetching the manual…"
            val file = runCatching { manuals.file(game) }
                .getOrElse { _notice.value = "Couldn't fetch the manual: ${it.message}"; return@launch }
            _notice.value = null
            _open.value = file
        }
    }

    fun hide() {
        fetching?.cancel()
        _notice.value = null
        _open.value = null
    }

    /**
     * The viewer showing [file] closed itself: with a [problem] (the file wouldn't open), the
     * cached copy is deleted so the next open fetches it again.
     */
    fun closed(file: File, problem: String?) {
        if (!_open.compareAndSet(file, null)) return
        if (problem == null) return
        _notice.value = problem
        scope.launch(Dispatchers.IO) { file.delete() }
    }

    /** Where [file] was left: the index of its first page on screen. */
    fun pageOf(file: File): Int = pages[file.name] ?: 0

    fun keepPage(file: File, page: Int) {
        pages[file.name] = page
    }
}
