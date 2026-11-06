package com.example.cpen321application

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/** Decode by dimensions before allocating pixels; re-encoding strips receipt metadata. */
internal fun compressReceiptImage(file: File): ByteArray {
    require(file.length() in 1..100L * 1024 * 1024) { "Choose an image smaller than 100 MB." }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a supported image." }
    var sample = 1
    while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 4_000_000 ||
        maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
    var bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IllegalArgumentException("Choose a supported image.")
    try {
        while (true) {
            val encoded = encodeReceiptAtQualities(bitmap)
            if (encoded != null) return encoded
            require(minOf(bitmap.width, bitmap.height) > 1) { "This image could not be compressed. Choose another image." }
            val smaller = Bitmap.createScaledBitmap(bitmap, maxOf(1, bitmap.width * 3 / 4), maxOf(1, bitmap.height * 3 / 4), true)
            bitmap.recycle(); bitmap = smaller
        }
    } finally { bitmap.recycle() }
}

private fun encodeReceiptAtQualities(bitmap: Bitmap): ByteArray? {
    for (quality in listOf(90, 80, 70, 60)) {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
        if (output.size() <= LedgerRepository.MAX_IMAGE_BYTES) return output.toByteArray()
    }
    return null
}
