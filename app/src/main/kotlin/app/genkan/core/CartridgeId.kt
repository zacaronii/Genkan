package app.genkan.core

import java.io.File

/**
 * A cartridge's id for the Yomikata catalog and its build, both read from the header in its file
 * ([SnesCartridge], [GbaCartridge], [GameBoyCartridge]): the build is the header's CRC-32, as 8
 * lower-case hex digits.
 * A PC Engine CD's is read so too, from its disc's boot block ([PceCdDisc]), and a HuCard's from
 * its ROM ([PceHuCard]), and a Famicom's from its PRG and CHR ([FamicomCartridge]). An N64
 * cartridge's build is its id and version instead ([N64Cartridge]), in [build], as a Disk System
 * disk's is ([FdsDisk]), and a Mega Drive cartridge's its serial and revision ([MegaDriveCartridge]).
 */
data class CartridgeId(val id: String, val headerCrc32: String, val build: String? = null) {
    /** As the player's process hands it over: the id, the CRC-32 and the build when there is one, by spaces. */
    fun wire(): String = listOfNotNull(id, headerCrc32, build).joinToString(" ")

    companion object {
        /** A [wire]'s id; null when it isn't one. */
        fun parse(raw: String): CartridgeId? {
            val parts = raw.split(' ')
            return when (parts.size) {
                2 -> CartridgeId(parts[0], parts[1])
                3 -> CartridgeId(parts[0], parts[1], parts[2])
                else -> null
            }
        }

        /** The id of [file], a game for RomM's platform [platform]; null for a console whose games aren't read so. */
        fun of(platform: String, file: File): CartridgeId? = when (platform) {
            "snes", "sfam" -> SnesCartridge.of(file)
            "gba" -> GbaCartridge.of(file)
            "gb", "gbc" -> GameBoyCartridge.of(file)
            "turbografx-cd" -> PceCdDisc.of(file)
            "tg16" -> PceHuCard.of(file)
            "n64" -> N64Cartridge.of(file)
            "nes", "famicom" -> FamicomCartridge.of(file)
            "fds" -> FdsDisk.of(file)
            "genesis", "genesis-slash-megadrive", "megadrive" -> MegaDriveCartridge.of(file)
            else -> null
        }
    }
}
