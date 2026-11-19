package app.genkan.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The game last started from Genkan: the reader files its lines and thread choice under it. */
@Singleton
class NowPlaying @Inject constructor() {

    /** [platform] is the library's platform slug; [rommRomId] is set for RomM games. */
    data class Game(val key: String, val title: String, val platform: String? = null, val rommRomId: Long? = null)

    private val _game = MutableStateFlow(NONE)
    val game: StateFlow<Game> = _game.asStateFlow()

    /** [id] is the library id: a RomM rom id, or the PC and Apps shelves' negative ids. */
    fun started(id: Long, title: String, platform: String? = null) {
        _game.value = Game("game:$id", title, platform, id.takeIf { it > 0 })
    }

    companion object {
        /** Lines that arrive before any game was started from Genkan. */
        val NONE = Game("none", "No game")
    }
}
