package app.genkan.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The games the built-in player unpacked (see [GameFile]), kept in [root] until their game is removed. */
@Singleton
class UnpackedGames(private val root: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(GameFile.cacheRoot(context.noBackupFilesDir))

    /** Removes what was unpacked for the game [romId]. */
    fun forget(romId: Long) = GameFile.forget(root, GameFile.keyFor(romId, File("")))
}
