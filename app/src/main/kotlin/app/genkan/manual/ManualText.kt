package app.genkan.manual

import app.genkan.ocr.MlKitJapaneseOcr
import app.genkan.ocr.OcrEngine
import app.genkan.ocr.ScreenText
import app.genkan.ui.ManualPages
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The text on a manual's pages, read by OCR the first time a page is tapped and kept for the
 * rest of the run, so a second tap on the same page answers at once. Pages are read at the width
 * the viewer shows them unzoomed, from a render of their own, never the one on screen (which may
 * be twice as wide while zoomed).
 */
@Singleton
class ManualText(private val engine: OcrEngine) {
    @Inject constructor(mlKit: MlKitJapaneseOcr) : this(mlKit as OcrEngine)

    /**
     * A page of a manual: [manual] is the file it came from, [version] that file's time and length.
     * A manual replaced at the same name (a newer one from the server) is another version, so the
     * old one's text is never taken for it.
     */
    private data class Key(val manual: String, val version: Long, val page: Int)

    private fun keyOf(manual: String, page: Int): Key =
        java.io.File(manual).let { Key(manual, it.lastModified() * 31 + it.length(), page) }

    private val read = object : LinkedHashMap<Key, ScreenText>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ScreenText>) = size > KEPT_PAGES
    }
    private val lock = Mutex()

    /** Whether [page] of [manual] has been read, so a tap on it needs no OCR. */
    suspend fun isRead(manual: String, page: Int): Boolean = lock.withLock { keyOf(manual, page) in read }

    /**
     * The character under a tap at ([x], [y]) (fractions of the page) on [page] of [manual], or
     * null where no text is: the page is rendered [widthPx] wide and read on its first tap.
     */
    suspend fun hit(manual: String, pages: ManualPages, page: Int, x: Float, y: Float, widthPx: Int): ScreenText.Hit? =
        textOf(manual, pages, page, widthPx).hit(x, y)

    private suspend fun textOf(manual: String, pages: ManualPages, page: Int, widthPx: Int): ScreenText = lock.withLock {
        val key = keyOf(manual, page)
        read[key]?.let { return it }
        val bitmap = pages.render(page, widthPx) ?: return ScreenText.EMPTY
        val text = try {
            ScreenText.of(engine.read(bitmap), 0, 0, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
        read[key] = text
        text
    }

    private companion object {
        /** Pages kept read: a few KB each, for paging back and forth through a manual. */
        const val KEPT_PAGES = 64
    }
}
