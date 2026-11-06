package com.example.cpen321application

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

internal class LedgerDocumentsController(private val model: LedgerViewModel) {
    var pendingDocument: ByteArray? = null
        private set
    private var cameraFile: File? = null

    fun chooseDocument(uri: Uri) = with(model) {
        runOperation("Reading document…") {
            val selected = withContext(Dispatchers.IO) {
                val resolver = model.getApplication<Application>().contentResolver
                var name = "Selected document"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty().take(180)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) require(cursor.getLong(sizeIndex) <= 100L * 1024 * 1024) {
                            "This file is too large. PDFs and CSVs support up to 20 MB; source images up to 100 MB."
                        }
                    }
                }
                val mime = documentMime(name, resolver.getType(uri).orEmpty())
                val temp = File.createTempFile("import-", ".tmp", model.getApplication<Application>().cacheDir)
                try {
                    resolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= if (mime.startsWith("image/")) 100L * 1024 * 1024 else LedgerRepository.MAX_DOCUMENT_BYTES.toLong()) { "This file is too large. PDFs and CSVs support up to 20 MB; source images up to 100 MB." }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: throw IllegalArgumentException("This document could not be opened. Choose it again.")
                    require(temp.length() > 0) { "This file is empty." }
                    val compressed = mime.startsWith("image/") && temp.length() > LedgerRepository.MAX_IMAGE_BYTES
                    val bytes = if (compressed) compressReceiptImage(temp) else temp.readBytes()
                    Triple(if (compressed) name.substringBeforeLast('.') + ".jpg" else name.ifBlank { "Selected document" }, if (compressed) "image/jpeg" else mime, bytes)
                } finally { temp.delete() }
            }
            setDocument(selected.first, selected.second, selected.third)
        }
    }

    private fun setDocument(name: String, suppliedMime: String, bytes: ByteArray) {
        with(model) {
            val mime = documentMime(name, suppliedMime)
            imports.clearImport()
            documentName = name
            documentMime = mime
            documentSize = bytes.size
            pendingDocument = bytes
            bills.documentChosen(mime)
            page = LedgerPage.Import
        }
    }

    fun prepareCamera(): Uri? {
        with(model) {
            if (busy) return null
            return try {
                cameraFile?.delete()
                val app = model.getApplication<Application>()
                val folder = File(app.cacheDir, "ledger-captures").apply { mkdirs() }
                val file = File.createTempFile("receipt-", ".jpg", folder)
                cameraFile = file
                FileProvider.getUriForFile(app, "${app.packageName}.ledger.files", file)
            } catch (_: java.io.IOException) { cameraUnavailable(); null }
            catch (_: SecurityException) { cameraUnavailable(); null }
            catch (_: IllegalArgumentException) { cameraUnavailable(); null }
        }
    }

    fun cameraResult(success: Boolean) {
        with(model) {
            val file = cameraFile ?: return
            cameraFile = null
            if (!success) { file.delete(); return }
            runOperation("Reading photo…") {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        require(file.length() in 1..100L * 1024 * 1024) { "Choose an image smaller than 100 MB." }
                        if (file.length() > LedgerRepository.MAX_IMAGE_BYTES) compressReceiptImage(file) else file.readBytes()
                    }
                    setDocument("Receipt photo.jpg", "image/jpeg", bytes)
                } finally { file.delete() }
            }
        }
    }

    fun cameraUnavailable() {
        with(model) {
            cameraFile?.delete()
            cameraFile = null
            error = "No camera app is available. Choose an existing image instead."
        }
    }

    fun discardBytes() { pendingDocument = null }

    fun close() { cameraFile?.delete(); cameraFile = null; discardBytes() }
}

private fun documentMime(name: String, suppliedMime: String): String {
    return when {
                name.endsWith(".csv", true) || suppliedMime in listOf("text/csv", "application/csv", "text/comma-separated-values") -> "text/csv"
                name.endsWith(".pdf", true) || suppliedMime == "application/pdf" -> "application/pdf"
                suppliedMime in listOf("image/jpeg", "image/png", "image/webp") -> suppliedMime
                name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
                name.endsWith(".png", true) -> "image/png"
                name.endsWith(".webp", true) -> "image/webp"
                else -> throw IllegalArgumentException("Choose a CSV, PDF, JPEG, PNG or WebP file.")
            }
}
