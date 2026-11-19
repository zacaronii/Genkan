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

    fun set(inFront: Boolean) {
        _inFront.value = inFront
    }
}
