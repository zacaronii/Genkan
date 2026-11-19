package app.genkan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.library.LibrarySync
import app.genkan.romm.RomMSession
import app.genkan.romm.RomMState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Screen { LOADING, LOGIN, LIBRARY, SETTINGS }

/** Top-screen navigation: the library when signed in, the login otherwise, settings on Start. */
@HiltViewModel
class AppViewModel @Inject constructor(
    session: RomMSession,
    private val librarySync: LibrarySync,
) : ViewModel() {

    private val settingsOpen = MutableStateFlow(false)

    val screen: StateFlow<Screen> = combine(session.state, settingsOpen) { state, settings ->
        when {
            settings -> Screen.SETTINGS
            state is RomMState.Loading -> Screen.LOADING
            state is RomMState.SignedIn -> Screen.LIBRARY
            else -> Screen.LOGIN
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Screen.LOADING)

    init {
        // Refresh the library whenever an account becomes active (app start or a new sign-in),
        // and drop it on sign-out so another account never sees the last one's games.
        viewModelScope.launch {
            session.state
                .map { (it as? RomMState.SignedIn)?.account?.let { a -> a.baseUrl + a.userId } }
                .distinctUntilChanged()
                .collect { accountKey ->
                    if (accountKey != null) librarySync.sync() else if (session.state.value is RomMState.SignedOut) librarySync.clear()
                }
        }
    }

    fun openSettings() {
        settingsOpen.value = true
    }

    fun closeSettings() {
        settingsOpen.value = false
    }
}
