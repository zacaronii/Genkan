package app.genkan.core

import android.view.KeyEvent

/**
 * The built-in player's shortcuts: Select held with another button. While Select is held, a
 * shortcut's button never reaches the game; Select itself reaches it only when it's let go with no
 * shortcut used ([Verdict.tapSelect]), so a game still gets its Select presses. Buttons that
 * aren't shortcuts pass as they are.
 */
class PadChords(private val bindings: Map<Int, Shortcut> = DEFAULTS) {

    enum class Shortcut { SAVE_STATE, LOAD_STATE, NEXT_SLOT, PREVIOUS_SLOT, FAST_FORWARD, QUIT }

    /**
     * What to do with a key: [consume] it (the game doesn't see it), shortcuts [started] by it,
     * held ones [ended] by it, and whether to [tapSelect] (send the game a Select press now).
     */
    data class Verdict(
        val consume: Boolean,
        val started: Shortcut? = null,
        val ended: List<Shortcut> = emptyList(),
        val tapSelect: Boolean = false,
    )

    private var selectHeld = false
    private var used = false
    /** Shortcut buttons down now, by key code. */
    private val held = mutableMapOf<Int, Shortcut>()

    fun key(keyCode: Int, down: Boolean, repeat: Int = 0): Verdict {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (down) {
                if (repeat == 0) {
                    selectHeld = true
                    used = false
                }
                return Verdict(consume = true)
            }
            selectHeld = false
            return Verdict(consume = true, tapSelect = !used)
        }
        if (!down) {
            val shortcut = held.remove(keyCode) ?: return Verdict(consume = false)
            return Verdict(consume = true, ended = listOf(shortcut))
        }
        if (keyCode in held) return Verdict(consume = true)
        val shortcut = bindings[keyCode]?.takeIf { selectHeld } ?: return Verdict(consume = false)
        used = true
        held[keyCode] = shortcut
        return Verdict(consume = true, started = shortcut)
    }

    companion object {
        val DEFAULTS: Map<Int, Shortcut> = mapOf(
            KeyEvent.KEYCODE_BUTTON_R1 to Shortcut.SAVE_STATE,
            KeyEvent.KEYCODE_BUTTON_L1 to Shortcut.LOAD_STATE,
            KeyEvent.KEYCODE_DPAD_RIGHT to Shortcut.NEXT_SLOT,
            KeyEvent.KEYCODE_DPAD_LEFT to Shortcut.PREVIOUS_SLOT,
            KeyEvent.KEYCODE_BUTTON_R2 to Shortcut.FAST_FORWARD,
            KeyEvent.KEYCODE_BUTTON_START to Shortcut.QUIT,
        )

        /** How many frames fast-forward runs per frame: less for the heavier consoles. */
        fun fastForwardSpeed(platform: String?): Int = if (platform in setOf("psx", "nds", "psp")) 2 else 3
    }
}
