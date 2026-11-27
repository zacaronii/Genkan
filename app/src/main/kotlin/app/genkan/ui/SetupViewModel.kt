package app.genkan.ui

import app.genkan.BuildConfig
import android.content.Context
import app.genkan.anki.AnkiConnect
import app.genkan.art.ArtCache
import app.genkan.core.TuningStore
import app.genkan.manual.Manuals
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import app.genkan.anki.AnkiMiner
import app.genkan.anki.AnkiSettings
import app.genkan.anki.AnkiStatus
import app.genkan.core.ScreenLook
import app.genkan.core.ScreenLooks
import app.genkan.stats.ReadingLogPusher
import app.genkan.stats.ReadingLogStatus
import app.genkan.yomikata.CatalogStatus
import app.genkan.yomikata.YomikataCatalog
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
import kotlinx.coroutines.launch
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
    /** Opens a console's page: the row's console (SetupViewModel.focusedConsole). */
    CONSOLE,
    /** The player learns each game's speed step. */
    LEARNING,
    INSERT_ANIMATION,
    /** The shelf's focused game as a full box, or its case. */
    FULL_BOX,
    PREFETCH_MANUALS,
    FACE_A_ON_RIGHT,
    /** The bonded Bluetooth headphones, a press to connect one. */
    HEADPHONES,
    WORDS,
    /** Memory samples (Reading). */
    YOMIKATA,
    /** Yomikata's catalog repository and samples inbox (Connections). */
    YOMIKATA_SERVERS,
    /** The third-party notices and their licences (About). */
    NOTICE,
    /** The manuals kept for offline, cleared on a second press (Storage). */
    CLEAR_MANUALS,
    /** The covers and art, cleared on a second press (Storage). */
    CLEAR_ART,
    /** The games with a step of their own (Advanced). */
    TUNING_STEPS,
    /** Genkan's log, redacted, to Download (Advanced). */
    SAVE_LOG,
    /** AnkiConnect's address and key, and the word-audio source (Connections). */
    ANKI_SERVER,
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
    private val yomikataCatalog: YomikataCatalog,
    ankiMiner: AnkiMiner,
    readingLog: ReadingLogPusher,
    looks: ScreenLooks,
    private val ankiSettings: AnkiSettings,
    private val ankiConnect: AnkiConnect,
    private val manuals: Manuals,
    val tuningStore: TuningStore,
    private val logSaver: LogSaver,
    @ApplicationContext private val context: Context,
) : ViewModel() {


    private val _usageAccess = MutableStateFlow(sessionTracker.hasUsageAccess())
    /** Without it, play time runs from launch until Genkan is back in front, sleep included. */
    val usageAccess: StateFlow<Boolean> = _usageAccess.asStateFlow()

    /** The grant happens in system settings, so re-read it whenever Genkan resumes. */
    fun refresh() {
        _usageAccess.value = sessionTracker.hasUsageAccess()
        _shizuku.value = privileged.state()
        checkServers()
    }

    private val _ankiReach = MutableStateFlow<AnkiReach?>(null)
    /** Whether AnkiConnect answered when last asked; null until asked. */
    val ankiReach: StateFlow<AnkiReach?> = _ankiReach.asStateFlow()

    /**
     * Asks the servers the status card shows whether they answer: AnkiConnect for its version
     * (sending nothing). kechimochi's line is its pusher's, which sends on its own every few minutes.
     */
    fun checkServers() {
        refreshStorage()
        viewModelScope.launch {
            val config = ankiSettings.current()
            _ankiReach.value = if (!config.configured) {
                AnkiReach.NotSet
            } else {
                runCatching { ankiConnect.version(config) }.fold(
                    { AnkiReach.Reachable(it) },
                    { AnkiReach.Unreachable(it.message ?: it.javaClass.simpleName) },
                )
            }
        }
    }

    private val _shizuku = MutableStateFlow(privileged.state())
    /** Whether DuckStation's and Dolphin's saves (in their own Android/data) can be synced. */
    val shizuku: StateFlow<Privileged.State> = _shizuku.asStateFlow()

    /** What Storage holds (the manuals, the art), in bytes; null until measured. */
    private val storage = MutableStateFlow<Pair<Long?, Long?>>(null to null)
    private val pressAgain = PressAgain()
    /** The Clear row waiting for its second press. */
    private val pressAgainArmed = MutableStateFlow<SetupAction?>(null)
    private val tuningCount = tuningStore.changes.map { tuningStore.all().size }
    /** What the last Save a log did. */
    private val logSaved = MutableStateFlow<String?>(null)

    /** Everything the page shows; a press by pad or by touch reads the switches from here alike. */
    @Suppress("UNCHECKED_CAST")
    val state: StateFlow<SetupState> = combine(
        dualScreen.status, romm.state, librarySync.status, usageAccess, shizuku,
        ppssppText.enabled, azaharText.enabled, playerSettings.enabled, playerSettings.samples, dictionaries.all,
        librarySettings.insertAnimation, librarySettings.fullBox, librarySettings.prefetchManuals,
        playerSettings.aOnRight, armsxText.enabled, yomikataCatalog.status, ankiMiner.status, readingLog.status,
        playerSettings.learning, looks.consoles, playerSettings.lowLatencyOff, playerSettings.faceSwapped, playerSettings.coreChoice,
        ankiReach, storage, pressAgainArmed, tuningCount, logSaved,
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
            catalog = v[15] as CatalogStatus,
            anki = v[16] as AnkiStatus,
            readingLog = v[17] as ReadingLogStatus,
            version = VERSION,
            nowMs = System.currentTimeMillis(),
            learning = v[18] as Boolean,
            looks = v[19] as Map<String, ScreenLook>,
            lowLatencyOff = v[20] as Set<String>,
            faceSwapped = v[21] as Set<String>,
            coreChoice = v[22] as Map<String, String>,
            ankiReach = v[23] as AnkiReach?,
            manualsBytes = (v[24] as Pair<Long?, Long?>).first,
            artBytes = (v[24] as Pair<Long?, Long?>).second,
            armed = v[25] as SetupAction?,
            tuningSteps = v[26] as Int,
            logSaved = v[27] as String?,
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
            version = VERSION,
        ),
    )

    init {
        // The status card's catalog line: the cached index, fetched again only when it's stale.
        viewModelScope.launch { runCatching { yomikataCatalog.refresh() } }
    }

    /** Measures what Storage holds: the manuals and the art. */
    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            storage.value = manuals.size() to ArtCache.directory(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    /**
     * A press on a Clear row: true on the second press within [PressAgain.WINDOW_MS], the first
     * arming it (its line says to press again, until the window passes).
     */
    fun confirmed(action: SetupAction): Boolean {
        val now = System.currentTimeMillis()
        if (pressAgain.press(action, now)) {
            pressAgainArmed.value = null
            return true
        }
        pressAgainArmed.value = action
        viewModelScope.launch {
            delay(PressAgain.WINDOW_MS)
            if (pressAgain.armed(System.currentTimeMillis()) == null) pressAgainArmed.value = null
        }
        return false
    }

    fun clearManuals() {
        viewModelScope.launch(Dispatchers.IO) {
            manuals.clearAll()
            refreshStorage()
        }
    }

    /** The covers and art: the disk and memory caches, fetched again as the shelf shows them. */
    fun clearArt() {
        viewModelScope.launch(Dispatchers.IO) {
            val loader = coil3.SingletonImageLoader.get(context)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
            refreshStorage()
        }
    }

    fun saveLog() {
        viewModelScope.launch {
            logSaved.value = "Saving…"
            logSaved.value = logSaver.save().fold({ "Saved as Download/$it (on this device only)" }, { "Not saved: ${it.message ?: it.javaClass.simpleName}" })
        }
    }

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

    /** The console of the row the pad is on, for a press that opens its page. */
    fun focusedConsole(): String? = with(_focus.value) {
        (groups.getOrNull(group)?.rows?.getOrNull(row) as? SettingsRow.Console)?.platform
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

    private companion object {
        /** The version About shows: the build's name, its kind, and the commit it was built from. */
        val VERSION = "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}, ${BuildConfig.GIT_COMMIT})"
    }
}
