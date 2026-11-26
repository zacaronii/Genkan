package app.genkan.launch

import app.genkan.core.CoreDef
import app.genkan.core.GameFile
import java.io.File

/** Which file the built-in player starts a game from. */
object BuiltInBoot {

    /**
     * [rom] when [core] plays it; for a playlist (m3u) it can't open, the first disc it lists, when
     * the core plays that (YabaSanshiro takes a Saturn game's discs, not their playlist); else null.
     */
    fun fileFor(core: CoreDef, rom: File): File? {
        if (GameFile.takes(core, rom)) return rom
        if (!rom.extension.equals("m3u", ignoreCase = true)) return null
        return LaunchCommandBuilder.firstListed(rom)?.takeIf { GameFile.takes(core, it) }
    }
}
