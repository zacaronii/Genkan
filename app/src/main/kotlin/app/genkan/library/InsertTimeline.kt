package app.genkan.library

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * When the pieces of starting a game happen, around the insert animation (the disc or cartridge
 * leaving its case and going into the machine). All times are milliseconds from the press of A.
 *
 * The game is got ready (its save pulled from RomM, firmware fetched, the emulator chosen) while
 * the animation plays. It starts at [launchAt]: once it is ready, and [leadMs] before the
 * animation ends, so an external emulator's window, which takes a moment to appear, lands as the
 * animation finishes. The built-in player has no lead: it starts, and the animation is cut, when
 * both are done. Nothing waits on the animation once it has ended or been skipped, so the
 * animation never adds time beyond its own length.
 */
data class InsertTimeline(val animationMs: Long, val leadMs: Long) {

    /** When the animation ends: at its full length, or when it was skipped ([skippedAtMs]). */
    fun animationEnd(skippedAtMs: Long?): Long =
        if (skippedAtMs == null) animationMs else min(animationMs, max(0, skippedAtMs))

    /** When to start the game, given it was ready at [readyAtMs]. */
    fun launchAt(readyAtMs: Long, skippedAtMs: Long?): Long =
        max(readyAtMs, animationEnd(skippedAtMs) - leadMs).coerceAtLeast(0)

    /** When the animation is taken down: the game has started and the animation has ended. */
    fun cutAt(readyAtMs: Long, skippedAtMs: Long?): Long =
        max(launchAt(readyAtMs, skippedAtMs), animationEnd(skippedAtMs))

    companion object {
        /** The animation's length at the normal animator speed (D). */
        const val ANIMATION_MS = 1_500L

        /** How long before the animation ends an external emulator is started (D). */
        const val EXTERNAL_LEAD_MS = 400L

        /**
         * The animation's length: none when switched off in settings or when the system's
         * animator duration scale is 0 (animations off in developer options or for
         * accessibility), else [ANIMATION_MS] stretched by that scale.
         */
        fun animationMs(enabled: Boolean, animatorScale: Float, baseMs: Long = ANIMATION_MS): Long =
            if (!enabled || !(animatorScale > 0f)) 0L else (baseMs * animatorScale).roundToLong()

        /** The timeline for a game started in the built-in player ([builtIn]) or an emulator app. */
        fun forLaunch(animationMs: Long, builtIn: Boolean): InsertTimeline =
            InsertTimeline(animationMs, if (builtIn || animationMs == 0L) 0L else min(EXTERNAL_LEAD_MS, animationMs))
    }
}

/** What a key press does while the insert animation may be showing. */
enum class InsertKey {
    /** Not the animation's: the screen handles it as usual. */
    PASS,
    /** Used up and ignored: a held button's repeats. */
    CONSUME,
    /** A fresh press of a pad button: the animation skips to its end. */
    SKIP;

    companion object {
        /**
         * For a key-down while the animation is [showing]: only a fresh press ([repeatCount] 0) of
         * a pad button ([isPadKey]) skips. Its repeats are used up without skipping, since holding
         * A, which started the game, sends them from about 400 ms on. Other keys (volume, say) pass.
         */
        fun decide(showing: Boolean, isPadKey: Boolean, repeatCount: Int): InsertKey = when {
            !showing || !isPadKey -> PASS
            repeatCount > 0 -> CONSUME
            else -> SKIP
        }
    }
}
