package app.genkan.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class QrCodeTest {

    @Test
    fun `the approval link survives an encode and decode`() {
        val url = "https://romm.example.com/pair/device?user_code=XJ4HEX3F"
        val matrix = encodeQr(url)
        val scale = 8
        val width = matrix.width * scale
        val pixels = IntArray(width * width) { i ->
            if (matrix[(i % width) / scale, (i / width) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels)))
        assertEquals(url, QRCodeReader().decode(bitmap).text)
    }
}
