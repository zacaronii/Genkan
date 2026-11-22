package app.genkan.library

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The game focused in the library, published for the bottom screen's details. */
@Singleton
class FocusedGame @Inject constructor() {

    /** [cover] is what the library card shows: a cover URL, or an app's icon. */
    data class Focus(val game: GameEntity, val platformName: String, val cover: Any?)

    private val _focus = MutableStateFlow<Focus?>(null)
    val focus: StateFlow<Focus?> = _focus.asStateFlow()

    private val _playRequests = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    /**
     * Game ids whose Play or Download button was tapped on the bottom screen. The library acts
     * on each as it does on A, so its notices and permission prompts stay on the top screen.
     */
    val playRequests: SharedFlow<Long> = _playRequests.asSharedFlow()

    fun set(focus: Focus?) {
        _focus.value = focus
    }

    private val _launching = MutableStateFlow<Long?>(null)

    /**
     * The game being started, from the press of A (or Play) until the top screen has moved to it.
     * The bottom screen goes to the reader then, in the same beat as the insert animation.
     */
    val launching: StateFlow<Long?> = _launching.asStateFlow()

    fun setLaunching(gameId: Long?) {
        _launching.value = gameId
    }

    /** Asks the library to play, download or cancel the download of [gameId], as A would. */
    fun requestPlay(gameId: Long) {
        _playRequests.tryEmit(gameId)
    }
}
