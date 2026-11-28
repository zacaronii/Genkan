package app.genkan.core

/**
 * The device's heat limit while a game plays: [LIMIT_C] or more for [WINDOW] seconds in a row is
 * too hot, whatever the game's step and whether it learns. The learner steps a game down at it
 * ([TuningLearner.CEILING_C]); this says so to the player, once, and again only after the device
 * has been under the limit for [CALM] seconds and reached it again. Fed one reading a second.
 */
class HeatCeiling {
    private var hot = 0
    private var calm = CALM
    private var said = false

    /** One second's hottest CPU zone ([hottestC], null when unread): true when the player should be told now. */
    fun second(hottestC: Int?): Boolean {
        if (hottestC == null) return false
        if (hottestC >= LIMIT_C) {
            hot++
            calm = 0
        } else {
            hot = 0
            if (++calm >= CALM) said = false
        }
        if (hot < WINDOW || said) return false
        said = true
        return true
    }

    companion object {
        /** The device's limit: no game plays above it for long. */
        const val LIMIT_C = 85
        /** Seconds in a row at the limit that count, not a spike. */
        const val WINDOW = 10
        /** Seconds under the limit before it's said again. */
        const val CALM = 60
    }
}
