package app.genkan.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Genkan's own launcher is in front on the top screen. While it is, the bottom screen
 * shows the focused game's details and OCR has no game text to read; once a game or another app
 * takes the top screen, the bottom one turns back into the reader.
 */
@Singleton
class LauncherPresence @Inject constructor() {
    private val _inFront = MutableStateFlow(false)
    val inFront: StateFlow<Boolean> = _inFront.asStateFlow()

    /**
     * The launcher instances started now. More than one at a time: when the home task is swiped
     * away, Android starts a new one, and the old one's stop can come after the new one's start;
     * a single flag set by each would then say "not in front" with the new launcher on screen.
     */
    private val started = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

    /** [launcher] (an activity) is started on the top screen. */
    @Synchronized
    fun started(launcher: Any) {
        started.add(launcher)
        _inFront.value = true
    }

    /** [launcher] stopped: in front only while another instance is still started. */
    @Synchronized
    fun stopped(launcher: Any) {
        started.remove(launcher)
        _inFront.value = started.isNotEmpty()
    }
}
