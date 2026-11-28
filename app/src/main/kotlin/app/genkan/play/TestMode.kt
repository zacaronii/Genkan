package app.genkan.play

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A device round's switch (files/test-mode, until when, in ms): while it's on, every game started
 * from Genkan is a test play, which Genkan records nothing of (see [NowPlaying.Game.test]). It is
 * read from the file at each start, so a restart neither drops it nor starts a game before it's
 * known, and it ends by itself. A debug build's receiver switches it (app.genkan.debug.TEST_MODE);
 * games it starts itself are test plays whether it's on or not.
 */
@Singleton
class TestMode internal constructor(private val file: File, private val clock: () -> Long) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, FILE), System::currentTimeMillis)

    /** Whether a game started now is a test play. */
    fun active(): Boolean = until()?.let { it > clock() } ?: false

    /** When the switch goes off by itself; null when it's off. */
    fun until(): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()

    /** On for [forMs], at most [MAX_MS]. */
    fun on(forMs: Long) {
        file.parentFile?.mkdirs()
        file.writeText((clock() + forMs.coerceIn(0, MAX_MS)).toString())
    }

    fun off() {
        file.delete()
    }

    companion object {
        const val FILE = "test-mode"
        /** A round's length at most: a switch left on ends within a day's play. */
        const val MAX_MS = 12 * 60 * 60 * 1000L
    }
}
