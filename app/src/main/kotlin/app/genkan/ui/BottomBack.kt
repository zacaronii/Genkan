package app.genkan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * Back for what is open on the bottom screen. Its window never takes focus (so the pad stays
 * with the top screen), and Back, the gesture included, goes to the focused window: the top
 * screen's. A viewer open down there (a manual, a screenshot) registers here while it's up, and
 * the top screen hands Back to the newest one before using Back itself. Main thread only.
 */
object BottomBack {
    private val closers = ArrayDeque<() -> Unit>()

    /** Whether something on the bottom screen takes Back. */
    val active: Boolean get() = closers.isNotEmpty()

    /** Closes the newest viewer on the bottom screen; false when none is open. */
    fun handle(): Boolean {
        val close = closers.lastOrNull() ?: return false
        close()
        return true
    }

    internal fun add(close: () -> Unit) = closers.addLast(close)

    internal fun remove(close: () -> Unit) {
        closers.remove(close)
    }
}

/**
 * While in composition, Back closes this viewer: a Back on the bottom screen's own window (as
 * [BackHandler]) and one the top screen hands over through [BottomBack].
 */
@Composable
fun BottomBackHandler(onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    BackHandler { current.value() }
    DisposableEffect(Unit) {
        val close: () -> Unit = { current.value() }
        BottomBack.add(close)
        onDispose { BottomBack.remove(close) }
    }
}
