package com.example.cpen321application

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AvatarCropUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var fixture: File? = null
    private var confirmed: String? = null
    private var cancelled = false

    private fun openCrop() {
        val file = File.createTempFile("crop-fixture-", ".png", context.cacheDir)
        fixture = file
        val image = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(image)
        canvas.drawColor(Color.RED)
        canvas.drawRect(600f, 0f, 1200f, 600f, android.graphics.Paint().apply { color = Color.BLUE })
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        compose.setContent { CPEN321ApplicationTheme {
            AvatarCropDialog(Uri.fromFile(file), onCancel = { cancelled = true }, onConfirm = { confirmed = it })
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("avatar-crop").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertNull(confirmed) }
    }

    @After fun cleanup() { fixture?.delete() }

    @Test fun cancelDoesNotConfirmOrReplaceTheAvatar() {
        openCrop()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(cancelled); assertNull(confirmed) }
    }

    @Test fun draggedCropConfirmsOnlyTheSelectedSquare() {
        openCrop()
        compose.onNodeWithTag("avatar-crop").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Use photo").performClick()
        compose.runOnIdle {
            val data = confirmed!!
            assertTrue(data.length <= 180000)
            val bytes = Base64.decode(data.substringAfter(','), Base64.NO_WRAP)
            val result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(384, result.width)
            assertEquals(384, result.height)
            val pixel = result.getPixel(192, 192)
            assertTrue(Color.blue(pixel) > Color.red(pixel) + 100)
            result.recycle()
            assertFalse(cancelled)
        }
    }
}
