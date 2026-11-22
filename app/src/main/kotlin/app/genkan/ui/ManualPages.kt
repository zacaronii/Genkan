package app.genkan.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.sqrt

/** A manual's pages, rendered one at a time for [ManualViewer]. */
interface ManualPages : Closeable {

    /** Each page's size in points, in order; its length is the page count. */
    val pageSizes: List<IntSize>

    /**
     * Page [index] rendered [widthPx] wide (fewer pixels for a huge page) on white, off the main
     * thread. Null once closed. The caller owns the bitmap and recycles it.
     */
    suspend fun render(index: Int, widthPx: Int): Bitmap?
}

/**
 * [ManualPages] over a PDF file with Android's [PdfRenderer]. The renderer allows one page open
 * at a time and isn't thread-safe, so every use goes through one lock, closing included.
 */
class PdfManualPages private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    override val pageSizes: List<IntSize>,
) : ManualPages {

    private val lock = Mutex()
    private var closed = false

    override suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed) return@withLock null
            renderer.openPage(index).use { page ->
                val (width, height) = renderSize(page.width, page.height, widthPx)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    // PDF pages are transparent where nothing is printed.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    /** Closes once any render in progress is done; the viewer calls it as it goes away. */
    override fun close() {
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            lock.withLock {
                if (closed) return@withLock
                closed = true
                renderer.close()
                descriptor.close()
            }
        }
    }

    companion object {
        /** The most pixels one page may take (32 MB at 4 bytes each), however wide it's asked for. */
        const val MAX_PAGE_PIXELS = 8_000_000L

        /** Opens [file], reading every page's size up front so the viewer can lay them all out. */
        suspend fun open(file: File): PdfManualPages = withContext(Dispatchers.IO) {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(descriptor)
                val sizes = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { IntSize(it.width, it.height) } }
                PdfManualPages(descriptor, renderer, sizes)
            } catch (e: Exception) {
                descriptor.close()
                throw e
            }
        }

        /**
         * A page of [pageWidth] x [pageHeight] points drawn [widthPx] wide, keeping its shape,
         * scaled down as a whole when that would pass [MAX_PAGE_PIXELS].
         */
        fun renderSize(pageWidth: Int, pageHeight: Int, widthPx: Int): Pair<Int, Int> {
            val w = widthPx.coerceAtLeast(1)
            val h = (w.toLong() * pageHeight.coerceAtLeast(1) / pageWidth.coerceAtLeast(1)).coerceAtLeast(1)
            val pixels = w * h
            if (pixels <= MAX_PAGE_PIXELS) return w to h.toInt()
            val shrink = sqrt(MAX_PAGE_PIXELS.toDouble() / pixels)
            return (w * shrink).toInt().coerceAtLeast(1) to (h * shrink).toInt().coerceAtLeast(1)
        }
    }
}
