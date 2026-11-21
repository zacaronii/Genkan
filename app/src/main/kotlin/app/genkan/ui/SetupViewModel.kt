package app.genkan.ui

import androidx.lifecycle.ViewModel
import app.genkan.dictionary.Dictionaries
import app.genkan.home.DualScreenState
import app.genkan.input.PadKey
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
import javax.inject.Inject

/** Everything the settings page can do. Each one has exactly one row in [SettingsGroups.DEFAULT]. */
enum class SetupAction {
    ENABLE_HOME,
    DISABLE_HOME,
    SYNC_LIBRARY,
    DICTIONARIES,
    ANKI,
    READING_LOG,
    USAGE_ACCESS,
    SHIZUKU,
    PSP_DEBUGGER,
    AZAHAR_RPC,
    BUILT_IN_PLAYER,
    WORDS,
    YOMIKATA,
    SIGN_OUT,
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

    val groups: List<SettingsGroup> = SettingsGroups.DEFAULT

    private val _focus = MutableStateFlow(SettingsFocus())
    val focus: StateFlow<SettingsFocus> = _focus.asStateFlow()

    /**
     * Gamepad navigation. Returns the action to run when a row is pressed, null otherwise;
     * consumed tells the activity whether to swallow the key. B on the group list is not
     * consumed, so the activity closes settings.
     */
    fun onKey(key: PadKey): KeyResult {
        val current = _focus.value
        val next = SettingsNavigator.onKey(current, key, groups) ?: return KeyResult(consumed = false)
        _focus.value = next
        val pressed = key == PadKey.A && current.pane == SettingsPane.ROWS
        return KeyResult(consumed = true, action = if (pressed) actionFor(groups[next.group].rows[next.row]) else null)
    }

    /** A tap on a group in the list. */
    fun selectGroup(index: Int) {
        _focus.value = SettingsFocus(pane = SettingsPane.GROUPS, group = index)
    }

    /** A tap on a row: the gamepad picks up from there. */
    fun focusRow(group: Int, row: Int) {
        _focus.value = SettingsFocus(pane = SettingsPane.ROWS, group = group, row = row)
    }

    /**
     * The action a press on [row] runs now. Only Home's switch runs different actions for on and
     * off; the others flip with one action, so only Home's state is read here.
     */
    private fun actionFor(row: SettingsRow): SetupAction = when (row) {
        is SettingsRow.Button -> row.action
        is SettingsRow.Switch -> row.action(on = row.key == SwitchKey.HOME && SetupState.homeOn(dualScreen.status.value))
    }

    data class KeyResult(val consumed: Boolean, val action: SetupAction? = null)
}
