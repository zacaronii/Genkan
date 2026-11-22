package app.genkan.download

import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * What [DownloadService] runs: the queue until it is empty, then a stop, unless more work came in.
 *
 * A game queued just as the queue ran dry asks for a start while this loop still looks busy, so
 * that start launches nothing; stopping anyway left it QUEUED until Genkan restarted. So the stop
 * is decided on [main], where the service's starts arrive: with work left the loop goes round
 * again, and [stop] (the service's stopSelfResult) refuses when a start newer than the one read
 * from [latestStart] came in, and the loop goes round again too.
 */
internal class DownloadLoop(
    private val runQueue: suspend () -> Unit,
    private val hasWork: suspend () -> Boolean,
    private val latestStart: () -> Int,
    private val stop: (Int) -> Boolean,
    private val main: CoroutineContext,
) {
    suspend fun run() {
        do runQueue() while (!stopIfIdle())
    }

    private suspend fun stopIfIdle(): Boolean = withContext(main) {
        val startId = latestStart()
        if (hasWork()) false else stop(startId)
    }
}
