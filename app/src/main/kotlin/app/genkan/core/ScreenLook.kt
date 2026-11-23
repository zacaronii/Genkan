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

/**
 * How the built-in player draws a game's picture onto the screen: the player library's own
 * shaders, and RetroArch shaders from libretro/glsl-shaders kept in the app's assets ([file],
 * sampled linearly when [linear], as their presets say). [label] is the short name (chip,
 * button); [description] says what it does.
 */
enum class ScreenLook(val label: String, val description: String, val file: String? = null, val linear: Boolean = false) {
    DEFAULT("Default", "Smoothed as it's scaled up: soft edges, no pixels visible."),
    SHARP("Sharp", "Square, crisp pixels; only their edges are blended."),
    SHARP_BILINEAR("Sharp bilinear", "Whole-pixel blocks with a thin smoothed seam: crisp at any scale.", "sharp-bilinear.glsl", linear = true),
    SHARP_BILINEAR_SIMPLE("Sharp bilinear (simple)", "As Sharp bilinear, with a slightly softer seam.", "sharp-bilinear-simple.glsl", linear = true),
    CRT("CRT", "Dark lines between the rows, like an old television."),
    CRT_LOTTES("CRT Lottes", "Scanlines, a phosphor mask and a little glow: a fuller old-television look.", "crt-lottes.glsl"),
    CRT_EASYMODE("CRT Easymode", "Scanlines and a mask, sharp and bright.", "crt-easymode.glsl"),
    CRT_APERTURE("CRT Aperture", "An aperture-grille television: fine vertical stripes and glow.", "crt-aperture.glsl"),
    ZFAST_CRT("zfast CRT", "Light scanlines and mask, made to be cheap.", "zfast_crt.glsl", linear = true),
    LCD("LCD", "A faint grid between the pixels, like a handheld's screen."),
    LCD3X("LCD 3x", "Each pixel as a handheld's red, green and blue cells, with dark gaps.", "lcd3x.glsl"),
    LCD1X("LCD 1x", "A light pixel grid with scanlines.", "lcd1x.glsl"),
    LCD1X_NDS("LCD 1x (DS)", "LCD 1x with a DS screen's colours.", "lcd1x_nds.glsl"),
    LCD1X_PSP("LCD 1x (PSP)", "LCD 1x with a PSP screen's colours.", "lcd1x_psp.glsl"),
    ZFAST_LCD("zfast LCD", "A light pixel grid, made to be cheap.", "zfast_lcd.glsl", linear = true),
    DOT("Dot", "Each pixel as a soft round dot, like an old handheld's matrix.", "dot.glsl"),
    CUT("CUT", "Smooths along edges so slopes and curves lose their steps; light."),
    CUT2("CUT2", "Like CUT, a little smoother, and sharpens soft edges."),
    CUT3("CUT3", "The smoothest of the three, in three passes; the most work for the GPU."),
    ;

    /** Kinds of look, as the picker groups them. */
    enum class Family(val title: String) {
        PLAIN("Plain"),
        CRT("CRT"),
        LCD("LCD"),
        SMOOTHING("Smoothing"),
    }

    val family: Family
        get() = when (this) {
            DEFAULT, SHARP, SHARP_BILINEAR, SHARP_BILINEAR_SIMPLE -> Family.PLAIN
            CRT, CRT_LOTTES, CRT_EASYMODE, CRT_APERTURE, ZFAST_CRT -> Family.CRT
            LCD, LCD3X, LCD1X, LCD1X_NDS, LCD1X_PSP, ZFAST_LCD, DOT -> Family.LCD
            CUT, CUT2, CUT3 -> Family.SMOOTHING
        }

    /** The shader to draw with; [read] gives an asset's text (a RetroArch shader's file). */
    fun shader(read: (String) -> String): ShaderConfig = when (this) {
        DEFAULT -> ShaderConfig.Default
        SHARP -> ShaderConfig.Sharp
        CRT -> ShaderConfig.CRT
        LCD -> ShaderConfig.LCD
        CUT -> ShaderConfig.CUT()
        CUT2 -> ShaderConfig.CUT2()
        CUT3 -> ShaderConfig.CUT3()
        else -> RetroArchShader.adapt(read(SHADERS + file!!)).let { ShaderConfig.Custom(it.vertex, it.fragment, linear) }
    }

    companion object {
        /** Where the RetroArch shaders sit in the app's assets. */
        const val SHADERS = "shaders/"

        /** The look named [name], or Default for none or one this build doesn't know. */
        fun of(name: String?): ScreenLook = entries.firstOrNull { it.name == name } ?: DEFAULT

        /**
         * A console's look until one is chosen: pixels kept square where the picture is scaled
         * 4.5x to 5.6x (PS1, DS), an LCD grid for the Game Boys (6.75x to 7.5x), and the PSP as
         * it is, since it's already drawn at 3x.
         */
        private val DEFAULTS = mapOf(
            "psx" to SHARP,
            "snes" to SHARP,
            "sfam" to SHARP,
            "nds" to SHARP,
            "gb" to LCD,
            "gbc" to LCD,
            "gba" to LCD,
        )

        fun defaultFor(platform: String): ScreenLook = DEFAULTS[platform] ?: DEFAULT

        private fun family(family: Family): Set<ScreenLook> = entries.filter { it.family == family }.toSet()

        /** The LCD looks for any handheld; the DS's and PSP's own variants go to those consoles alone. */
        private val HANDHELD_LCD = setOf(LCD, LCD3X, LCD1X, ZFAST_LCD)

        /**
         * The looks that suit [platform]'s games, which the picker lists first; null for a console
         * not in the table (every look suits it). Plain ones suit every console. CRT ones suit what
         * was played on a television (PlayStation); LCD ones the handhelds, each LCD 1x variant
         * its own console, Dot the Game Boys' matrix screens. Smoothing suits 2D pixel art scaled
         * a long way (PlayStation, Game Boys, DS), not the PSP, already drawn at 3x.
         */
        fun suiting(platform: String?): Set<ScreenLook>? = when (platform) {
            "psx", "snes", "sfam" -> family(Family.PLAIN) + family(Family.CRT) + family(Family.SMOOTHING)
            "gb", "gbc", "gba" -> family(Family.PLAIN) + HANDHELD_LCD + DOT + family(Family.SMOOTHING)
            "nds" -> family(Family.PLAIN) + HANDHELD_LCD + LCD1X_NDS + family(Family.SMOOTHING)
            "psp" -> family(Family.PLAIN) + HANDHELD_LCD + LCD1X_PSP
            else -> null
        }

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
