package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.input.PadKey
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    dao: LibraryDao,
    private val session: RomMSession,
    private val librarySync: LibrarySync,
) : ViewModel() {

    private val _focus = MutableStateFlow(LibraryFocus())
    val focus: StateFlow<LibraryFocus> = _focus.asStateFlow()

    val platforms: StateFlow<List<PlatformEntity>> =
        dao.platforms().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val games: StateFlow<List<GameEntity>> =
        combine(platforms, _focus.map { it.platformIndex }.distinctUntilChanged()) { list, index -> list.getOrNull(index) }
            .map { it?.id }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.games(id) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val syncStatus: StateFlow<SyncStatus> = librarySync.status

    /** Set by the grid from its measured width, so up/down move by whole rows. */
    var columns: Int = 1

    fun coverUrl(game: GameEntity): String? =
        session.currentAccount()?.let { app.genkan.romm.RomMApiFactory.assetUrl(it.baseUrl, game.coverPath) }

    fun onKey(key: PadKey): Boolean {
        if (key == PadKey.Y) {
            sync(); return true
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

    fun selectGame(index: Int) = _focus.update { it.copy(pane = LibraryPane.GAMES, gameIndex = index) }

    fun sync() {
        viewModelScope.launch { librarySync.sync() }
    }
}
