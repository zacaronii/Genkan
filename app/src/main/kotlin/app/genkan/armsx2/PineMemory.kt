package app.genkan.armsx2

import app.genkan.memory.MemorySource

/**
 * A PS2 game's main memory (the EE's 32 MB at 0x00000000, as Yomikata's scripts address it)
 * through PINE. Reads go a page at a time and the pages are kept until [nextTick], so a script's
 * or a text search's many small reads in one tick cost a round trip for each page they touch, not
 * each read. A tick's pages aren't one moment of the game: ARMSX2 runs on while they're read.
 */
class PineMemory(private val client: PineClient) : MemorySource {

    private val pages = HashMap<Long, ByteArray>()

    override val regions: List<LongRange> = listOf(0L until RAM_SIZE)

    override val maxRead: Int = MAX_READ

    /** The game may have moved on: pages are read again from here. */
    @Synchronized
    fun nextTick() = pages.clear()

    /**
     * The EE sees its RAM at 0x00000000 and again through the uncached (0x20000000, 0x30000000)
     * and kernel (0x80000000, 0xA0000000) segments; a pointer into any of them is folded back.
     */
    override fun target(value: Long): Long? {
        val segment = value and 0xF0000000L
        if (segment !in SEGMENTS) return null
        val offset = value and 0x0FFFFFFFL
        return offset.takeIf { it < RAM_SIZE }
    }

    @Synchronized
    override fun read(address: Long, size: Int): ByteArray? {
        if (size !in 0..MAX_READ || address < 0 || address + size > RAM_SIZE) return null
        val first = address / PAGE
        val last = (address + size - 1) / PAGE
        val missing = (first..last).filter { it !in pages }
        if (missing.isNotEmpty()) {
            // Runs of missing pages, each read whole in as few messages as PINE allows.
            for (run in runs(missing)) {
                val bytes = client.read(run.first * PAGE, ((run.last - run.first + 1) * PAGE).toInt()) ?: return null
                for (page in run) pages[page] = bytes.copyOfRange(((page - run.first) * PAGE).toInt(), ((page - run.first + 1) * PAGE).toInt())
            }
        }
        val out = ByteArray(size)
        var at = address
        while (at < address + size) {
            val page = pages.getValue(at / PAGE)
            val from = (at % PAGE).toInt()
            val count = minOf(PAGE - from, address + size - at).toInt()
            page.copyInto(out, (at - address).toInt(), from, from + count)
            at += count
        }
        return out
    }

    override fun close() = Unit

    private fun runs(pages: List<Long>): List<LongRange> {
        val runs = mutableListOf<LongRange>()
        var start = pages.first()
        var end = start
        for (page in pages.drop(1)) {
            if (page == end + 1 && page - start < PAGES_PER_RUN) {
                end = page
            } else {
                runs += start..end
                start = page
                end = page
            }
        }
        runs += start..end
        return runs
    }

    companion object {
        const val RAM_SIZE = 32L shl 20
        const val PAGE = 4096L
        /** The most one read asks for: what the text search reads at a time elsewhere. */
        const val MAX_READ = 64 * 1024
        /** Pages read in one run: 64 KB, 8,192 reads, one message. */
        private const val PAGES_PER_RUN = 16
        private val SEGMENTS = setOf(0x00000000L, 0x20000000L, 0x30000000L, 0x80000000L, 0xA0000000L)
    }
}
