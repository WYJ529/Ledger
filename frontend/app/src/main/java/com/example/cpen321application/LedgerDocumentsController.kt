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
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) require(cursor.getLong(sizeIndex) <= LedgerRepository.MAX_DOCUMENT_BYTES) {
                            "This file is too large. Choose a file no larger than 5 MiB."
                        }
                    }
                }
                val bytes = resolver.openInputStream(uri)?.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= LedgerRepository.MAX_DOCUMENT_BYTES) { "This file is too large. Choose a file no larger than 5 MiB." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw IllegalArgumentException("This document could not be opened. Choose it again.")
                require(bytes.isNotEmpty()) { "This file is empty." }
                Triple(name.ifBlank { "Selected document" }, resolver.getType(uri).orEmpty(), bytes)
            }
            setDocument(selected.first, selected.second, selected.third)
        }
    }

    private fun setDocument(name: String, suppliedMime: String, bytes: ByteArray) {
        with(model) {
            val mime = when {
                name.endsWith(".csv", true) || suppliedMime in listOf("text/csv", "application/csv", "text/comma-separated-values") -> "text/csv"
                name.endsWith(".pdf", true) || suppliedMime == "application/pdf" -> "application/pdf"
                suppliedMime in listOf("image/jpeg", "image/png", "image/webp") -> suppliedMime
                name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
                name.endsWith(".png", true) -> "image/png"
                name.endsWith(".webp", true) -> "image/webp"
                else -> throw IllegalArgumentException("Choose a CSV, PDF, JPEG, PNG or WebP file.")
            }
            imports.clearImport()
            documentName = name
            documentMime = mime
            documentSize = bytes.size
            pendingDocument = bytes
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
                        require(file.length() in 1..LedgerRepository.MAX_DOCUMENT_BYTES.toLong()) { "This photo is too large. Choose an image no larger than 5 MiB." }
                        file.readBytes()
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
