package app.genkan.core

/**
 * A game's disks as its core gives them for swapping: [count] images, [current] the one in. A Disk
 * System game's are the sides of its disks, in order (disk 1 side A, side B, disk 2 side A…), as
 * FCEUmm gives them; a game on discs (an M3U of them) has one image a disc.
 */
data class DiskSet(val count: Int, val current: Int, val sides: Boolean) {
    /** The one after the one in, round to the first again: a side's other side, then the next disk. */
    val next: Int get() = (current + 1) % count

    /** What image [index] is, for the player: "Disk 1, side B", "Disc 2 of 3". */
    fun label(index: Int): String =
        if (sides) "Disk ${index / 2 + 1}, side ${'A' + index % 2}" else "Disc ${index + 1} of $count"

    /** Short, for the bar: "1B", "2/3". */
    fun short(index: Int): String = if (sides) "${index / 2 + 1}${'A' + index % 2}" else "${index + 1}/$count"

    companion object {
        /** The core whose images are a disk's sides. */
        const val SIDED_CORE = "fceumm"

        /** From the player's [count, current]; null for one disk or none. */
        fun of(raw: IntArray?, sides: Boolean): DiskSet? {
            if (raw == null || raw.size < 2 || raw[0] < 2) return null
            return DiskSet(raw[0], raw[1].coerceIn(0, raw[0] - 1), sides)
        }
    }
}
