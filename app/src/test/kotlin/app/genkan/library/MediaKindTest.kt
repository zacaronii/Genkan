package app.genkan.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaKindTest {

    @Test
    fun `the library's platforms`() {
        val expected = mapOf(
            "fds" to MediaKind.DISK_CARD,
            "3ds" to MediaKind.CARD,
            "nds" to MediaKind.CARD,
            "psx" to MediaKind.DISC,
            "ps2" to MediaKind.DISC,
            "psp" to MediaKind.UMD,
            "sfc" to MediaKind.CARTRIDGE,
            "snes" to MediaKind.CARTRIDGE,
            "gb" to MediaKind.CARTRIDGE,
            "gbc" to MediaKind.CARTRIDGE,
            "gba" to MediaKind.CARTRIDGE,
            "ngc" to MediaKind.DISC,
            "wii" to MediaKind.DISC,
            "n64" to MediaKind.CARTRIDGE,
            "nes" to MediaKind.CARTRIDGE,
        )
        expected.forEach { (slug, kind) -> assertEquals(slug, kind, MediaKind.forSlug(slug)) }
    }

    @Test
    fun `the rest of RomM's slugs`() {
        for (slug in listOf("tg16", "pce", "supergrafx", "nintendo-dsi")) assertEquals(slug, MediaKind.CARD, MediaKind.forSlug(slug))
        for (slug in listOf("sg1000", "msx", "msx2", "satellaview")) assertEquals(slug, MediaKind.CARTRIDGE, MediaKind.forSlug(slug))
        for (slug in listOf("ps4", "ps5")) assertEquals(slug, MediaKind.DISC, MediaKind.forSlug(slug))
        assertEquals(MediaKind.DISK_CARD, MediaKind.forSlug("64dd"))
    }

    @Test
    fun `unknown slugs and local shelves are unknown`() {
        assertEquals(MediaKind.UNKNOWN, MediaKind.forSlug("not-a-platform"))
        assertEquals(MediaKind.UNKNOWN, MediaKind.forSlug("pc"))
        assertEquals(MediaKind.UNKNOWN, MediaKind.forSlug(""))
        assertEquals(MediaKind.UNKNOWN, MediaKind.forSlug(null))
    }

    @Test
    fun `slugs are matched case-insensitively`() {
        assertEquals(MediaKind.UMD, MediaKind.forSlug("PSP"))
    }
}
