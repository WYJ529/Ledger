package com.example.cpen321application

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AccountAvatarTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun avatarCorrectsOrientationResizesAndStripsMetadata() {
        val file = File.createTempFile("avatar-test-", ".jpg", context.cacheDir)
        try {
            val source = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
            source.eraseColor(android.graphics.Color.BLUE)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            source.recycle()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_ARTIST, "Private metadata")
                saveAttributes()
            }
            val data = prepareAvatar(context, Uri.fromFile(file))
            assertTrue(data.startsWith("data:image/jpeg;base64,"))
            assertTrue(data.length <= 180000)
            val bytes = Base64.decode(data.substringAfter(','), Base64.NO_WRAP)
            val result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(384, result.width)
            assertEquals(384, result.height)
            result.recycle()
            bytes.inputStream().use { assertNull(ExifInterface(it).getAttribute(ExifInterface.TAG_ARTIST)) }
        } finally { file.delete() }
    }

    @Test fun invalidAndOversizeFilesAreRejectedBeforeUpload() {
        val file = File.createTempFile("avatar-invalid-test-", ".jpg", context.cacheDir)
        try {
            file.writeText("not an image")
            assertThrows(IllegalArgumentException::class.java) { prepareAvatar(context, Uri.fromFile(file)) }
            java.io.RandomAccessFile(file, "rw").use { it.setLength(20L * 1024 * 1024 + 1) }
            val failure = assertThrows(IllegalArgumentException::class.java) { prepareAvatar(context, Uri.fromFile(file)) }
            assertTrue(failure.message!!.contains("20 MB"))
        } finally { file.delete() }
    }
}
