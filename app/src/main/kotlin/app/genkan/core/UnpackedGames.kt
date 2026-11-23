package app.genkan.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The games the built-in player unpacked (see [GameFile]), kept in [root] until their game is
 * removed, and their save states ([SaveStates], in [states]), which go with it.
 */
@Singleton
class UnpackedGames(private val root: File, private val states: File? = null) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(GameFile.cacheRoot(context.noBackupFilesDir), File(context.filesDir, SaveStates.FOLDER))

    /** Removes what was unpacked for the game [romId], and its save states. */
    fun forget(romId: Long) {
        val key = GameFile.keyFor(romId, File(""))
        GameFile.forget(root, key)
        states?.let { SaveStates.forget(it, key) }
    }
}
