package app.genkan.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meiki OCR (rtr46/meikiocr, LGPL-3.0): a line detector and two character recognisers trained
 * on Japanese video games, run on ONNX Runtime from the upstream files bundled in assets/meiki.
 * Much better than ML Kit on game fonts (眺めた, 待ってる, 叱られた where ML Kit garbles them),
 * but slower: on the Thor about 130 ms to find the lines and 25 ms to read them, against ML
 * Kit's 20-35 ms, which is fine for a line that has already settled. The models load on first
 * use (about a second, 45 MB) and stay loaded.
 */
@Singleton
class MeikiOcr @Inject constructor(@ApplicationContext private val context: Context) : OcrEngine {
    override val name = "Meiki"

    private val env = OrtEnvironment.getEnvironment()
    private val lock = Mutex()
    private var models: Models? = null

    private class Models(val detect: OrtSession, val horizontal: OrtSession, val vertical: OrtSession)

    /** False when the build carries no models (they come from Gradle's downloadMeikiModels). */
    val available: Boolean by lazy {
        runCatching { context.assets.list("meiki")?.contains("detect.onnx") == true }.getOrDefault(false)
    }

    override suspend fun read(bitmap: Bitmap): List<OcrLine> = withContext(Dispatchers.Default) {
        lock.withLock {
            val m = models ?: load().also { models = it }
            val started = System.nanoTime()
            val found = detect(m.detect, bitmap)
            val detected = System.nanoTime()
            val (vertical, horizontal) = found.partition { it.box.vertical }
            val lines = recognise(m.horizontal, bitmap, horizontal, vertical = false) +
                recognise(m.vertical, bitmap, vertical, vertical = true)
            Log.d(
                TAG,
                "${found.size} lines: detect ${(detected - started) / 1_000_000} ms, " +
                    "recognise ${(System.nanoTime() - detected) / 1_000_000} ms",
            )
            lines
        }
    }

    private fun load(): Models {
        val started = System.nanoTime()
        // Plain CPU: XNNPACK measured no faster on the Thor (detect ~130 ms either way).
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(THREADS)
        }
        fun session(file: String) = env.createSession(context.assets.open("meiki/$file").use { it.readBytes() }, options)
        return Models(session("detect.onnx"), session("rec_horizontal.onnx"), session("rec_vertical.onnx")).also {
            Log.i(TAG, "Models loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }

    private fun detect(session: OrtSession, bitmap: Bitmap): List<MeikiPipeline.Line> {
        val scale = MeikiPipeline.detScale(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceIn(1, MeikiPipeline.DET_W)
        val h = (bitmap.height * scale).toInt().coerceIn(1, MeikiPipeline.DET_H)
        val input = MeikiPipeline.detInput(pixels(bitmap.scale(w, h)), w, h, scale)
        val shape = longArrayOf(1, 3, MeikiPipeline.DET_H.toLong(), MeikiPipeline.DET_W.toLong())
        return run(session, input.tensor, shape, 1, MeikiPipeline.DET_W, MeikiPipeline.DET_H) { result ->
            MeikiPipeline.lines(result.floats("boxes"), result.floats("scores"), input.scale, bitmap.width, bitmap.height)
        }
    }

    /** All lines of one orientation in one batch. */
    private fun recognise(session: OrtSession, bitmap: Bitmap, lines: List<MeikiPipeline.Line>, vertical: Boolean): List<OcrLine> {
        if (lines.isEmpty()) return emptyList()
        val canvasW = if (vertical) MeikiPipeline.REC_V_W else MeikiPipeline.REC_H_W
        val canvasH = if (vertical) MeikiPipeline.REC_V_H else MeikiPipeline.REC_H_H
        val canvas = canvasW * canvasH * 3
        val batch = FloatArray(canvas * lines.size)
        lines.forEachIndexed { i, line ->
            val c = MeikiPipeline.cropFor(line.box, bitmap.width, bitmap.height)
            val (w, h) = MeikiPipeline.recSize(c.width, c.height, vertical)
            val crop = Bitmap.createBitmap(bitmap, c.left, c.top, c.width, c.height).scale(w, h)
            MeikiPipeline.recInput(pixels(crop), w, h, vertical).copyInto(batch, i * canvas)
        }
        val shape = longArrayOf(lines.size.toLong(), 3, canvasH.toLong(), canvasW.toLong())
        return run(session, batch, shape, lines.size, canvasW, canvasH) { result ->
            val codes = result.ints("char_codes")
            val boxes = result.floats("boxes")
            val scores = result.floats("scores")
            val per = scores.size / lines.size
            lines.mapIndexed { i, line ->
                val text = MeikiPipeline.text(
                    codes.copyOfRange(i * per, (i + 1) * per),
                    boxes.copyOfRange(i * per * 4, (i + 1) * per * 4),
                    scores.copyOfRange(i * per, (i + 1) * per),
                    vertical,
                )
                OcrLine(text, line.box.top, line.box.left, line.box.height)
            }.filter { it.text.isNotBlank() }
        }
    }

    private fun <T> run(
        session: OrtSession,
        images: FloatArray,
        shape: LongArray,
        batch: Int,
        width: Int,
        height: Int,
        read: (OrtSession.Result) -> T,
    ): T {
        val sizes = LongArray(batch * 2) { if (it % 2 == 0) width.toLong() else height.toLong() }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(images), shape).use { imageTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(sizes), longArrayOf(batch.toLong(), 2)).use { sizeTensor ->
                session.run(mapOf("images" to imageTensor, "orig_target_sizes" to sizeTensor)).use { return read(it) }
            }
        }
    }

    private fun OrtSession.Result.floats(name: String): FloatArray {
        val buffer = (get(name).get() as OnnxTensor).floatBuffer
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun OrtSession.Result.ints(name: String): IntArray {
        val buffer = (get(name).get() as OnnxTensor).intBuffer
        return IntArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    private companion object {
        const val TAG = "MeikiOcr"
        const val THREADS = 4
    }
}
