package app.genkan.core

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import app.genkan.home.DualScreenState
import app.genkan.home.ScreenMode
import app.genkan.ocr.OcrReader
import app.genkan.ocr.ScreenText
import app.genkan.reader.NowPlaying
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.overlayStore: DataStore<Preferences> by preferencesDataStore(name = "overlay")

/** Whether the overlay is on before the user has chosen. */
object OverlayDefault {
    /** Systems with two screens of their own: the game takes both, the reader has none. */
    private val TWO_SCREEN_PLATFORMS = setOf("nds", "3ds", "n3ds")

    /** On with no second screen for the reader, or when the game's system uses both; else off. */
    fun on(mode: ScreenMode, platform: String?): Boolean = mode == ScreenMode.SINGLE || platform in TWO_SCREEN_PLATFORMS
}

/**
 * The overlay over a game in the built-in player: the lines OCR reads, boxed where they sit on
 * the game, each a tap away from the same lookup as the backlog's. Off or on by [OverlayDefault]
 * until the user flips it; their choice then stays.
 */
@Singleton
class GameOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val player: BuiltInPlayer,
    private val ocr: OcrReader,
    builtIn: BuiltInText,
    dualScreen: DualScreenState,
    nowPlaying: NowPlaying,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The user's choice, or null while they haven't made one. */
    private val choice = context.overlayStore.data.map { it[ON] }

    val enabled: StateFlow<Boolean> = combine(choice, dualScreen.status, nowPlaying.game) { chosen, status, game ->
        chosen ?: OverlayDefault.on(status.mode, game.platform)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // What the player draws: OCR's lines while on, nothing while off.
        scope.launch {
            combine(enabled, ocr.screen, player.game) { on, screen, game -> Triple(on, screen, game) }
                .distinctUntilChanged()
                .collect { (on, screen, game) -> game?.let { send(it, if (on) screen else ScreenText.EMPTY) } }
        }
        // A layout's choice, as a panel of its answers over the game, while the overlay is on.
        scope.launch {
            combine(enabled, builtIn.choice, player.game) { on, choice, game -> Triple(on, choice, game) }
                .distinctUntilChanged()
                .collect { (on, choice, game) -> game?.let { sendPanel(it, OverlayPanel.of(choice).takeIf { on }) } }
        }
    }

    /** Taps on a line of the overlay, to look up. */
    val taps = player.overlayTaps

    /** Whether there's a game to draw over (the built-in player is running one). */
    val available: StateFlow<Boolean> = player.game.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    fun toggle() {
        val on = !enabled.value
        scope.launch { context.overlayStore.edit { it[ON] = on } }
    }

    private fun sendPanel(game: CoreGame, panel: OverlayPanel?) {
        try {
            game.session.showPanel(panel?.toJson().orEmpty())
        } catch (e: RemoteException) {
            Log.w(TAG, "Player unreachable: $e")
        }
    }

    private fun send(game: CoreGame, screen: ScreenText) {
        try {
            game.session.showOverlay(screen.toJson())
        } catch (e: RemoteException) {
            Log.w(TAG, "Player unreachable: $e")
        }
    }

    private companion object {
        const val TAG = "GameOverlay"
        val ON = booleanPreferencesKey("on")
    }
}
