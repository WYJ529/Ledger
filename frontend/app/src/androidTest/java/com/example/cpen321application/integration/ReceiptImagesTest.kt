package com.example.cpen321application.integration

import com.example.cpen321application.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class ReceiptImagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun largeImageIsSampledAndBounded() {
        val file = File.createTempFile("receipt-sampled-", ".jpg", context.cacheDir)
        try {
            val source = Bitmap.createBitmap(6000, 3000, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.BLUE)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            source.recycle()
            val bytes = compressReceiptImage(file)
            assertTrue(bytes.size <= LedgerRepository.MAX_IMAGE_BYTES)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(decoded.width, decoded.height * 2)
            assertTrue(decoded.width.toLong() * decoded.height <= 4_000_000)
            assertTrue(maxOf(decoded.width, decoded.height) <= 4096)
            decoded.recycle()
        } finally { file.delete() }
    }

    @Test fun invalidOrOversizeSourcesFailBeforeDecodingPixels() {
        val file = File.createTempFile("receipt-invalid-", ".jpg", context.cacheDir)
        try {
            file.writeText("not an image")
            assertThrows(IllegalArgumentException::class.java) { compressReceiptImage(file) }
            RandomAccessFile(file, "rw").use { it.setLength(100L * 1024 * 1024 + 1) }
            val error = assertThrows(IllegalArgumentException::class.java) { compressReceiptImage(file) }
            assertTrue(error.message!!.contains("100 MB"))
        } finally { file.delete() }
    }
}
