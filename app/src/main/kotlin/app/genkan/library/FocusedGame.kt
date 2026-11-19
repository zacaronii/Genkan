package app.genkan.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    fun set(focus: Focus?) {
        _focus.value = focus
    }
}
