package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadQueue
import app.genkan.home.LauncherPresence
import app.genkan.launch.LocalShelves
import app.genkan.library.FocusedGame
import app.genkan.play.PlaySessionDao
import app.genkan.play.PlayTotals
import app.genkan.stats.ReadingDao
import app.genkan.stats.ReadingGameTotals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the bottom screen shows: the focused game while browsing, the reader otherwise. */
enum class CompanionMode { DETAILS, READER }

data class GameDetails(
    val focus: FocusedGame.Focus,
    val play: PlayTotals?,
    val reading: ReadingGameTotals,
    val download: DownloadEntity?,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GameDetailsViewModel @Inject constructor(
    launcher: LauncherPresence,
    focusedGame: FocusedGame,
    playSessions: PlaySessionDao,
    readingDao: ReadingDao,
    downloadQueue: DownloadQueue,
) : ViewModel() {

    /** Set when the backlog is asked for while browsing; cleared whenever the top screen changes. */
    private val readerPinned = MutableStateFlow(false)

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
            // Play sessions and downloads exist only for RomM games; reading is kept per game key.
            val play = if (LocalShelves.isLocal(game)) flowOf(null) else playSessions.totals(game.id)
            combine(play, readingDao.gameTotals("game:${game.id}"), downloadQueue.downloads.map { it[game.id] }) { p, r, d ->
                GameDetails(focus, p, r, d)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { launcher.inFront.drop(1).collect { readerPinned.value = false } }
    }

    fun showReader() {
        readerPinned.value = true
    }

    fun showDetails() {
        readerPinned.value = false
    }
}
