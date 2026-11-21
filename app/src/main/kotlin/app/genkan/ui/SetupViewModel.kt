package app.genkan.ui

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import app.genkan.dictionary.Dictionaries
import app.genkan.home.DualScreenState
import app.genkan.library.LibrarySync
import app.genkan.play.SessionTracker
import app.genkan.privileged.Privileged
import app.genkan.azahar.AzaharText
import app.genkan.core.PlayerSettings
import app.genkan.ppsspp.PpssppText
import app.genkan.romm.RomMSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

enum class SetupAction(val label: String) {
    ENABLE_HOME("Enable Genkan as Home"),
    DISABLE_HOME("Disable Genkan as Home"),
    SYNC_LIBRARY("Sync library now"),
    DICTIONARIES("Dictionaries"),
    ANKI("Anki"),
    READING_LOG("Reading log"),
    USAGE_ACCESS("Usage access (exact play time)"),
    SHIZUKU("Allow Genkan in Shizuku (emulator saves)"),
    PSP_DEBUGGER("PSP text from PPSSPP's debugger: switch on or off"),
    AZAHAR_RPC("3DS text from Azahar's RPC server: switch on or off"),
    BUILT_IN_PLAYER("Built-in player for GB, GBA, DS, PS1 and PSP: switch on or off"),
    WORDS("Words: known and ignored, lookup counts"),
    YOMIKATA("Yomikata: memory samples and inbox"),
    SIGN_OUT("Sign out of RomM"),
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    val dualScreen: DualScreenState,
    val romm: RomMSession,
    val librarySync: LibrarySync,
    val dictionaries: Dictionaries,
    private val sessionTracker: SessionTracker,
    val privileged: Privileged,
    val ppssppText: PpssppText,
    val azaharText: AzaharText,
    val playerSettings: PlayerSettings,
) : ViewModel() {


    private val _usageAccess = MutableStateFlow(sessionTracker.hasUsageAccess())
    /** Without it, play time runs from launch until Genkan is back in front, sleep included. */
    val usageAccess: StateFlow<Boolean> = _usageAccess.asStateFlow()

    /** The grant happens in system settings, so re-read it whenever Genkan resumes. */
    fun refresh() {
        _usageAccess.value = sessionTracker.hasUsageAccess()
        _shizuku.value = privileged.state()
    }

    private val _shizuku = MutableStateFlow(privileged.state())
    /** Whether DuckStation's and Dolphin's saves (in their own Android/data) can be synced. */
    val shizuku: StateFlow<Privileged.State> = _shizuku.asStateFlow()

    val actions: List<SetupAction> = SetupAction.entries

    private val _focusIndex = MutableStateFlow(0)
    val focusIndex: StateFlow<Int> = _focusIndex.asStateFlow()

    /**
     * Gamepad navigation. Returns the action to run when the confirm button is pressed, null
     * otherwise; consumed tells the activity whether to swallow the key.
     */
    fun onKey(keyCode: Int): KeyResult = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> move(-1)
        KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
            KeyResult(consumed = true, action = actions[_focusIndex.value])
        else -> KeyResult(consumed = false)
    }

    fun focus(action: SetupAction) {
        _focusIndex.value = actions.indexOf(action)
    }

    private fun move(delta: Int): KeyResult {
        _focusIndex.update { (it + delta).coerceIn(0, actions.lastIndex) }
        return KeyResult(consumed = true)
    }

    data class KeyResult(val consumed: Boolean, val action: SetupAction? = null)
}
