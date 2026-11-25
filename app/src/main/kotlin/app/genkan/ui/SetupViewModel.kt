package app.genkan.ui

import androidx.lifecycle.ViewModel
import app.genkan.dictionary.Dictionaries
import app.genkan.home.DualScreenState
import app.genkan.input.PadKey
import app.genkan.library.LibrarySync
import app.genkan.library.LibraryViewSettings
import app.genkan.play.SessionTracker
import app.genkan.privileged.Privileged
import app.genkan.armsx2.ArmsxText
import app.genkan.azahar.AzaharText
import app.genkan.core.PlayerSettings
import app.genkan.ppsspp.PpssppText
import app.genkan.romm.RomMSession
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.dictionary.DictionaryEntity
import app.genkan.home.DualScreenStatus
import app.genkan.library.SyncStatus
import app.genkan.romm.RomMState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    ARMSX2_PINE,
    BUILT_IN_PLAYER,
    SCREEN_LOOK,
    INSERT_ANIMATION,
    /** The shelf's focused game as a full box, or its case. */
    FULL_BOX,
    PREFETCH_MANUALS,
    FACE_A_ON_RIGHT,
    /** The bonded Bluetooth headphones, a press to connect one. */
    HEADPHONES,
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
    val armsxText: ArmsxText,
    val playerSettings: PlayerSettings,
    val librarySettings: LibraryViewSettings,
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

    /** Everything the page shows; a press by pad or by touch reads the switches from here alike. */
    @Suppress("UNCHECKED_CAST")
    val state: StateFlow<SetupState> = combine(
        dualScreen.status, romm.state, librarySync.status, usageAccess, shizuku,
        ppssppText.enabled, azaharText.enabled, playerSettings.enabled, playerSettings.samples, dictionaries.all,
        librarySettings.insertAnimation, librarySettings.fullBox, librarySettings.prefetchManuals,
        playerSettings.aOnRight, armsxText.enabled,
    ) { v ->
        SetupState(
            status = v[0] as DualScreenStatus,
            romm = v[1] as RomMState,
            sync = v[2] as SyncStatus,
            usageAccess = v[3] as Boolean,
            shizuku = v[4] as Privileged.State,
            pspDebugger = v[5] as Boolean,
            azaharRpc = v[6] as Boolean,
            builtInPlayer = v[7] as Boolean,
            textSamples = v[8] as Boolean,
            dictionaries = v[9] as List<DictionaryEntity>,
            insertAnimation = v[10] as Boolean,
            fullBox = v[11] as Boolean,
            prefetchManuals = v[12] as Boolean,
            faceAOnRight = v[13] as Boolean,
            armsxPine = v[14] as Boolean,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SetupState(
            status = dualScreen.status.value,
            romm = romm.state.value,
            sync = librarySync.status.value,
            usageAccess = usageAccess.value,
            shizuku = shizuku.value,
            pspDebugger = false,
            azaharRpc = false,
            builtInPlayer = false,
            textSamples = false,
            dictionaries = emptyList(),
        ),
    )

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

    /** The action a press on [row] runs now: the same answer a tap gets (see [SetupState.actionFor]). */
    private fun actionFor(row: SettingsRow): SetupAction = state.value.actionFor(row)

    data class KeyResult(val consumed: Boolean, val action: SetupAction? = null)
}
