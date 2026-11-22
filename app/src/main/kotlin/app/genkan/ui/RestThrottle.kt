package app.genkan.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * How long the shelf's focus must have rested for a step's details to show on the bottom screen
 * at once; steps closer together than this (a held D-pad) show once the focus has rested this long.
 */
const val DETAILS_FOLLOW_MS = 120L

/**
 * This flow's values, the first after a rest at once and the ones that follow within [restMs]
 * of the last change held back until the changes have stopped for [restMs] (then only the
 * latest). A value [atOnce] says no to always waits for the rest. [now] is the clock, in
 * milliseconds.
 */
fun <T> Flow<T>.restThrottle(
    restMs: Long,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
    atOnce: (T) -> Boolean = { true },
): Flow<T> = channelFlow {
    var lastChange: Long? = null
    var held: Job? = null
    collect { value ->
        val at = now()
        held?.cancel()
        val previous = lastChange
        lastChange = at
        if ((previous == null || at - previous >= restMs) && atOnce(value)) {
            send(value)
        } else {
            held = launch {
                delay(restMs)
                send(value)
            }
        }
    }
}
