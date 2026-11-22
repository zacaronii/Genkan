package app.genkan.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.swordfish.libretrodroid.ShaderConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** How the built-in player draws a game's picture onto the screen: the player library's own shaders. */
enum class ScreenLook(val label: String) {
    DEFAULT("Default"),
    SHARP("Sharp"),
    CRT("CRT"),
    LCD("LCD"),
    CUT("CUT"),
    CUT2("CUT2"),
    CUT3("CUT3"),
    ;

    /** The next look, round to the first. */
    fun next(): ScreenLook = entries[(ordinal + 1) % entries.size]

    val shader: ShaderConfig
        get() = when (this) {
            DEFAULT -> ShaderConfig.Default
            SHARP -> ShaderConfig.Sharp
            CRT -> ShaderConfig.CRT
            LCD -> ShaderConfig.LCD
            CUT -> ShaderConfig.CUT()
            CUT2 -> ShaderConfig.CUT2()
            CUT3 -> ShaderConfig.CUT3()
        }

    companion object {
        /** The look named [name], or Default for none or one this build doesn't know. */
        fun of(name: String?): ScreenLook = entries.firstOrNull { it.name == name } ?: DEFAULT

        /**
         * A game's own look, or "the console's" (null), stepped once: the console's, then each look,
         * then the console's again.
         */
        fun nextOverride(current: ScreenLook?): ScreenLook? = when (current) {
            null -> entries.first()
            entries.last() -> null
            else -> current.next()
        }

        /**
         * A console's look until one is chosen: pixels kept square where the picture is scaled
         * 4.5x to 5.6x (PS1, DS), an LCD grid for the Game Boys (6.75x to 7.5x), and the PSP as
         * it is, since it's already drawn at 3x.
         */
        private val DEFAULTS = mapOf(
            "psx" to SHARP,
            "nds" to SHARP,
            "gb" to LCD,
            "gbc" to LCD,
            "gba" to LCD,
        )

        fun defaultFor(platform: String): ScreenLook = DEFAULTS[platform] ?: DEFAULT

        /** What a game is drawn with: its own look if it has one, else its console's. */
        fun resolve(game: ScreenLook?, console: ScreenLook): ScreenLook = game ?: console
    }
}

private val Context.lookStore: DataStore<Preferences> by preferencesDataStore(name = "screen_look")

/**
 * The look of each console in the built-in player, by platform slug, and the games that have
 * one of their own. A console has [ScreenLook.defaultFor] until a look is chosen for it.
 */
@Singleton
class ScreenLooks @Inject constructor(@ApplicationContext private val context: Context) {

    /** The looks chosen for consoles; a console not in it has [ScreenLook.defaultFor]. */
    val consoles: Flow<Map<String, ScreenLook>> = context.lookStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            key.name.removePrefix(CONSOLE).takeIf { key.name.startsWith(CONSOLE) }?.let { it to ScreenLook.of(value as? String) }
        }.toMap()
    }

    fun console(platform: String): Flow<ScreenLook> =
        context.lookStore.data.map { prefs -> prefs[consoleKey(platform)]?.let(ScreenLook::of) ?: ScreenLook.defaultFor(platform) }

    suspend fun setConsole(platform: String, look: ScreenLook) {
        context.lookStore.edit {
            if (look == ScreenLook.defaultFor(platform)) it.remove(consoleKey(platform)) else it[consoleKey(platform)] = look.name
        }
    }

    /** The game's own look, or null when it takes its console's. */
    fun game(romId: Long): Flow<ScreenLook?> = context.lookStore.data.map { prefs -> prefs[gameKey(romId)]?.let(ScreenLook::of) }

    suspend fun setGame(romId: Long, look: ScreenLook?) {
        context.lookStore.edit { if (look == null) it.remove(gameKey(romId)) else it[gameKey(romId)] = look.name }
    }

    /** What the game is drawn with when it starts. */
    suspend fun forLaunch(romId: Long, platform: String): ScreenLook =
        ScreenLook.resolve(game(romId).first(), console(platform).first())

    private companion object {
        const val CONSOLE = "console_"
        fun consoleKey(platform: String) = stringPreferencesKey(CONSOLE + platform)
        fun gameKey(romId: Long) = stringPreferencesKey("game_$romId")
    }
}
