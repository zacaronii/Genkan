package app.genkan.core

import java.io.File

/**
 * A cartridge's id for the Yomikata catalog and its build, both read from the header in its file
 * ([SnesCartridge], [GbaCartridge]): the build is the header's CRC-32, as 8 lower-case hex digits.
 * A PC Engine CD's is read so too, from its disc's boot block ([PceCdDisc]), and a HuCard's from
 * its ROM ([PceHuCard]).
 */
data class CartridgeId(val id: String, val headerCrc32: String) {
    companion object {
        /** The id of [file], a game for RomM's platform [platform]; null for a console whose games aren't read so. */
        fun of(platform: String, file: File): CartridgeId? = when (platform) {
            "snes", "sfam" -> SnesCartridge.of(file)
            "gba" -> GbaCartridge.of(file)
            "turbografx-cd" -> PceCdDisc.of(file)
            "tg16" -> PceHuCard.of(file)
            else -> null
        }
    }
}
