package com.example.cpen321application.integration

import com.example.cpen321application.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AccountAvatarTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun avatarResizesAndBoundsEncodedPayload() {
        val file = File.createTempFile("avatar-test-", ".jpg", context.cacheDir)
        try {
            val source = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
            source.eraseColor(android.graphics.Color.BLUE)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            source.recycle()
            var confirmed: String? = null
            compose.setContent { CPEN321ApplicationTheme {
                AvatarCropDialog(Uri.fromFile(file), onCancel = {}, onConfirm = { confirmed = it })
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("avatar-crop").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Use photo").performClick()
            val data = compose.runOnIdle { requireNotNull(confirmed) }
            assertTrue(data.startsWith("data:image/jpeg;base64,"))
            assertTrue(data.length <= 180000)
            val bytes = Base64.decode(data.substringAfter(','), Base64.NO_WRAP)
            val result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(384, result.width)
            assertEquals(384, result.height)
            result.recycle()
        } finally { file.delete() }
    }

    @Test fun invalidAndOversizeFilesAreRejectedBeforeUpload() {
        val file = File.createTempFile("avatar-invalid-test-", ".jpg", context.cacheDir)
        try {
            file.writeText("not an image")
            assertThrows(IllegalArgumentException::class.java) { readAvatarBitmap(context, Uri.fromFile(file)) }
            java.io.RandomAccessFile(file, "rw").use { it.setLength(20L * 1024 * 1024 + 1) }
            val failure = assertThrows(IllegalArgumentException::class.java) { readAvatarBitmap(context, Uri.fromFile(file)) }
            assertTrue(failure.message!!.contains("20 MB"))
        } finally { file.delete() }
    }
}
