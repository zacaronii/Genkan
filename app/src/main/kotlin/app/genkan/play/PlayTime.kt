package app.genkan.play

/** The usage events play time is measured from; mirrors the UsageEvents.Event kinds used. */
enum class UsageKind { RESUMED, PAUSED, STOPPED, SCREEN_OFF }

data class UsageSample(
    val timestamp: Long,
    val kind: UsageKind,
    val packageName: String? = null,
    val className: String? = null,
)

data class Measured(val endedAt: Long, val durationMs: Long)

/**
 * Measures a session from usage events. The emulator counts as in front while any of its
 * activities is resumed, so the menu-to-game hop inside Dolphin is one stretch, and sleep or a
 * trip to another app is left out. Without usage access there are no events; the session then
 * runs from launch until Genkan came back. Events from before [startedAt] are read too, adding no
 * time: Genkan opens the session once the launch is done, so the game is often already in front
 * by then, its one RESUMED before the start.
 */
object PlayTime {

    /**
     * [classes]: only these activities of [packageName] count as the game. For the built-in
     * player, whose package is Genkan's own, so the launcher and the bottom screen don't.
     */
    fun measure(startedAt: Long, now: Long, packageName: String, events: List<UsageSample>?, classes: Set<String>? = null): Measured {
        if (events == null) return Measured(now, (now - startedAt).coerceAtLeast(0))
        val resumed = HashSet<String>()
        var stretchStart: Long? = null
        var total = 0L
        var lastSeen = startedAt
        for (event in events.sortedBy { it.timestamp }) {
            if (event.timestamp > now) continue
            val wasInFront = resumed.isNotEmpty()
            when {
                event.kind == UsageKind.SCREEN_OFF -> resumed.clear()
                event.packageName != packageName -> continue
                classes != null && event.className !in classes -> continue
                event.kind == UsageKind.RESUMED -> resumed += event.className.orEmpty()
                else -> resumed -= event.className.orEmpty()
            }
            // Before the start, the events only say what's in front by then.
            val at = maxOf(event.timestamp, startedAt)
            if (!wasInFront && resumed.isNotEmpty()) stretchStart = at
            if (wasInFront && resumed.isEmpty()) {
                total += at - stretchStart!!
                if (event.timestamp >= startedAt) lastSeen = event.timestamp
            }
        }
        if (resumed.isNotEmpty()) {
            total += now - stretchStart!!
            lastSeen = now
        }
        return Measured(lastSeen, total)
    }
}
