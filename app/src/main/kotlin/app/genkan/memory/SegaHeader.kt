package app.genkan.memory

/**
 * A Saturn or Dreamcast disc by its header (a Saturn's system ID, a Dreamcast's IP.BIN), read as
 * Yomikata's identify.py reads it, so the two name a game alike: the product number (10 bytes, spaces
 * trimmed), with "-D" and the disc's number after it when the disc is one of a set ("CD-1/2",
 * "GD-ROM1/3"), since the discs of a set share a product number. The build is the id, "-" and the
 * version (6 bytes, "V1.005"). The game's boot code copies the header into RAM, where Genkan reads it.
 */
object SegaHeader {
    data class Release(val id: String, val build: String)

    private class Kind(val product: Int, val version: Int, val device: Int, val disc: Regex)

    private val KINDS = mapOf(
        "SEGA SEGASATURN " to Kind(0x20, 0x2A, 0x38, Regex("CD-([0-9]+)/([0-9]+)")),
        "SEGA SEGAKATANA " to Kind(0x40, 0x4A, 0x20, Regex("GD-ROM([0-9]+)/([0-9]+)")),
    )
    private val PRODUCT = Regex("[A-Z0-9][A-Z0-9-]{2,9}")
    private const val LENGTH = 0x50

    /** The release whose header is at [at] in [bytes]; null when none is there or its product number isn't one. */
    fun read(bytes: ByteArray, at: Int = 0): Release? {
        if (at < 0 || bytes.size - at < LENGTH) return null
        val kind = KINDS[text(bytes, at, 16)] ?: return null
        val product = text(bytes, at + kind.product, 10).trim()
        val version = text(bytes, at + kind.version, 6).trim()
        if (!PRODUCT.matches(product)) return null
        val disc = kind.disc.find(String(bytes, at + kind.device, 16, Charsets.ISO_8859_1))
        val number = disc?.groupValues?.get(1)?.toIntOrNull()
        val of = disc?.groupValues?.get(2)?.toIntOrNull()
        val id = if (number != null && of != null && of > 1) "$product-D$number" else product
        return Release(id, "$id-$version")
    }

    private fun text(bytes: ByteArray, at: Int, length: Int) = String(bytes, at, length, Charsets.US_ASCII)
}
