package com.example.cpen321application

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class ReceiptImagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun largeImageIsSampledOrientedAndBounded() {
        val file = File.createTempFile("receipt-sampled-", ".jpg", context.cacheDir)
        try {
            val source = Bitmap.createBitmap(6000, 3000, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.BLUE)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            source.recycle()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_ARTIST, "Private metadata")
                saveAttributes()
            }
            val bytes = compressReceiptImage(file)
            assertTrue(bytes.size <= LedgerRepository.MAX_IMAGE_BYTES)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertTrue(decoded.height > decoded.width)
            assertTrue(decoded.width.toLong() * decoded.height <= 4_000_000)
            assertTrue(maxOf(decoded.width, decoded.height) <= 4096)
            decoded.recycle()
            bytes.inputStream().use { assertNull(ExifInterface(it).getAttribute(ExifInterface.TAG_ARTIST)) }
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
