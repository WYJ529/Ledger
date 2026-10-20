package com.example.cpen321application

import android.content.Context
import android.database.sqlite.SQLiteException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

data class LedgerRate(val rate: String, val date: String, val source: String)

/** Injectable boundary for device persistence tests; the default implementation calls the backend. */
interface LedgerRemote {
    fun quote(from: String, to: String, date: String): LedgerRate
    fun extract(fileName: String, mimeType: String, bytes: ByteArray, defaultCurrency: String): List<LedgerImportRow>
    fun extractLocalStatement(fileName: String, mimeType: String, bytes: ByteArray, defaultCurrency: String): List<LedgerImportRow> =
        throw LedgerException("Local statement preview is unavailable. Use CSV or explicitly choose AI extraction.")
}

/** Synchronous by design: call from Dispatchers.IO. All mutations are serialized across instances. */
class LedgerRepository(
    context: Context,
    private val remote: LedgerRemote = LedgerHttpRemote(),
    databaseName: String = "ledger_guest.db"
) : AutoCloseable {
    private val database = LedgerDatabase(context, databaseName)

    fun load(): List<LedgerRecord> = operation { database.load() }

    fun baseCurrency(): String = operation { database.baseCurrency() }

    fun save(input: LedgerInput, id: String? = null, revision: Int? = null, requestId: String): LedgerRecord = operation {
        validateRequestId(requestId)
        val value = LedgerDomain.normalize(input)
        val fingerprint = fingerprint("save", id.orEmpty(), revision?.toString().orEmpty(), inputFingerprint(value))
        replay(requestId, fingerprint)?.let { return@operation LedgerDatabase.recordFromJson(JSONObject(it)) }
        requireValid(value)
        val previous = if (id == null) null else database.find(id) ?: throw LedgerException("This transaction no longer exists. Refresh the ledger.")
        if (previous != null && previous.revision != revision) throw LedgerException("This transaction changed. Refresh it before saving again.")
        if (id == null && revision != null) throw LedgerException("A new transaction cannot have an existing revision.")
        val now = Instant.now().toString()
        val amount = LedgerDomain.parseMinor(value.amount, value.currency)
        val reportingCurrency = database.baseCurrency()
        // An edit remains the same transaction. Keep its original historical rate when
        // only the description/category/notes change (or its amount is corrected). This
        // avoids network dependence and prevents a late-published daily quote changing
        // the value of a previously recorded transaction.
        val fx = previous?.takeIf {
            it.input.currency == value.currency && it.input.date == value.date && it.fx.baseCurrency == reportingCurrency
        }?.let { saved ->
            saved.fx.copy(convertedMinor = LedgerDomain.convertMinor(amount, value.currency, reportingCurrency, saved.fx.rate))
        } ?: conversion(value, amount, reportingCurrency)
        val record = LedgerRecord(id ?: UUID.randomUUID().toString(), value, amount,
            fx, previous?.source ?: "manual", previous?.sourceFileName.orEmpty(),
            previous?.let { nextRevision(it.revision) } ?: 1, previous?.createdAt ?: now, now)
        LedgerDomain.summary(database.load().filter { it.id != record.id } + record, database.baseCurrency())
        database.transaction {
            if (previous == null) database.insert(record)
            else if (!database.update(record, previous.revision)) throw LedgerException("This transaction changed. Refresh it before saving again.")
            database.rememberRequest(requestId, fingerprint, LedgerDatabase.recordToJson(record).toString())
        }
        record
    }

    fun delete(id: String, revision: Int) = operation {
        val record = database.find(id) ?: throw LedgerException("This transaction no longer exists. Refresh the ledger.")
        if (record.revision != revision || !database.delete(id, revision)) throw LedgerException("This transaction changed. Refresh it before deleting.")
        Unit
    }

    /** Delete exactly the reviewed revisions; a stale row or SQL failure rolls back the whole selection. */
    fun deleteMany(records: List<LedgerRecord>): Int = operation {
        if (records.isEmpty()) throw LedgerException("Select at least one transaction to delete.")
        if (records.map { it.id }.toSet().size != records.size) throw LedgerException("Select each transaction only once.")
        database.transaction {
            records.forEach { record ->
                if (!database.delete(record.id, record.revision)) {
                    throw LedgerException("A selected transaction changed or was removed. Nothing was deleted. Refresh and select again.")
                }
            }
        }
        records.size
    }

    /** Fetch every required historical rate before the atomic preference/record update. */
    fun setBaseCurrency(currency: String): List<LedgerRecord> = operation {
        if (currency !in LedgerDomain.currencies) throw LedgerException("Choose a supported reporting currency.")
        val existing = database.load()
        if (database.baseCurrency() == currency) return@operation existing
        val now = Instant.now().toString()
        val converted = existing.map { record -> record.copy(
            fx = conversion(record.input, record.amountMinor, currency),
            revision = nextRevision(record.revision), updatedAt = now
        ) }
        // Also verify totals before committing so a currency change cannot break the summary.
        LedgerDomain.summary(converted, currency)
        database.transaction {
            converted.zip(existing).forEach { (newRecord, oldRecord) ->
                if (!database.update(newRecord, oldRecord.revision)) throw LedgerException("The ledger changed. Retry the reporting currency change.")
            }
            database.setBaseCurrency(currency)
        }
        converted
    }

    fun summary(records: List<LedgerRecord>): LedgerSummary = operation {
        LedgerDomain.summary(records, database.baseCurrency())
    }

    fun previewCsv(text: String, statementCurrency: String? = null): List<LedgerImportRow> = operation {
        LedgerDomain.parseCsv(text, database.baseCurrency(), statementCurrency)
    }

    fun previewDocument(fileName: String, mimeType: String, bytes: ByteArray, localOnly: Boolean = false): List<LedgerImportRow> = operation {
        if (bytes.isEmpty() || bytes.size > MAX_DOCUMENT_BYTES) throw LedgerException("Choose a nonempty document of 5 MB or smaller.")
        validateFileName(fileName)
        if (!matchesDocumentType(mimeType, bytes)) throw LedgerException("Choose a JPEG, PNG, WebP, or PDF file matching its file type.")
        val rows = if (localOnly) remote.extractLocalStatement(fileName, mimeType, bytes, database.baseCurrency())
            else remote.extract(fileName, mimeType, bytes, database.baseCurrency())
        if (rows.isEmpty()) throw LedgerException("No transactions were found. Try a clearer document or enter the transaction manually.")
        if (rows.size > LedgerDomain.MAX_IMPORT_ROWS || rows.map { it.rowId }.toSet().size != rows.size) {
            throw LedgerException("The document service returned too many or invalid rows. Split the document and retry.")
        }
        rows
    }

    fun review(rows: List<LedgerImportRow>, existing: List<LedgerRecord>): List<LedgerRowReview> =
        LedgerDomain.review(rows, existing)

    fun commitImport(rows: List<LedgerImportRow>, source: String, fileName: String, requestId: String): Int = operation {
        validateRequestId(requestId)
        if (source !in listOf("csv", "receipt")) throw LedgerException("Unsupported import source.")
        validateFileName(fileName)
        val fingerprint = fingerprint("import", source, fileName, *rows.map {
            fingerprint(it.rowId, inputFingerprint(LedgerDomain.normalize(it.input)), it.selected.toString(), it.acknowledgeDuplicate.toString())
        }.toTypedArray())
        replay(requestId, fingerprint)?.let { return@operation it.toInt() }
        val reviewed = LedgerDomain.review(rows, database.load()).filter { it.row.selected }
        if (reviewed.isEmpty()) throw LedgerException("Select at least one transaction to import.")
        if (reviewed.any { it.errors.isNotEmpty() }) throw LedgerException("Fix the highlighted fields in all selected rows before importing.")
        if (reviewed.any { it.duplicates.isNotEmpty() && !it.row.acknowledgeDuplicate }) {
            throw LedgerException("Review possible duplicates. Skip each duplicate or explicitly choose to keep it.")
        }
        val currency = database.baseCurrency()
        val now = Instant.now().toString()
        val records = reviewed.map { review ->
            val value = LedgerDomain.normalize(review.row.input)
            val amount = LedgerDomain.parseMinor(value.amount, value.currency)
            LedgerRecord(UUID.randomUUID().toString(), value, amount, conversion(value, amount, currency), source, fileName,
                createdAt = now, updatedAt = now)
        }
        LedgerDomain.summary(database.load() + records, currency)
        // The rows and retry receipt commit together: a crash cannot create a partial batch or a duplicate retry.
        database.transaction {
            records.forEach(database::insert)
            database.rememberRequest(requestId, fingerprint, records.size.toString())
        }
        records.size
    }

    private fun conversion(input: LedgerInput, amount: Long, currency: String): LedgerFx {
        if (input.currency == currency) return LedgerFx(currency, "1", input.date, "Same currency", amount)
        val rate = database.cachedRate(input.currency, currency, input.date) ?: remote.quote(input.currency, currency, input.date).also {
            validateRate(it, input.date)
            // Validate numeric form even if this rate would round a tiny transaction down to zero.
            LedgerDomain.convertMinor(amount, input.currency, currency, it.rate)
            database.cacheRate(input.currency, currency, input.date, it)
        }
        validateRate(rate, input.date)
        return LedgerFx(currency, rate.rate, rate.date, rate.source,
            LedgerDomain.convertMinor(amount, input.currency, currency, rate.rate))
    }

    private fun validateRate(rate: LedgerRate, requestedDate: String) {
        val actual = try { LocalDate.parse(rate.date) } catch (_: Exception) { throw LedgerException("The exchange service returned an invalid rate date.") }
        val requested = LocalDate.parse(requestedDate)
        if (!Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(rate.date) || actual > requested ||
            actual < requested.minusDays(7) || actual < LocalDate.of(1999, 1, 4) || rate.source.isBlank() || rate.source.length > 160) {
            throw LedgerException("The exchange service returned invalid rate details.")
        }
    }

    private fun requireValid(input: LedgerInput) {
        val errors = LedgerDomain.validate(input)
        if (errors.isNotEmpty()) throw LedgerException(errors.values.joinToString(" "))
    }

    private fun replay(requestId: String, fingerprint: String): String? {
        val request = database.request(requestId) ?: return null
        if (request.first != fingerprint) throw LedgerException("This retry belongs to different content. Start a new save or import.")
        return request.second
    }

    private fun validateRequestId(requestId: String) {
        if (!Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(requestId)) {
            throw LedgerException("Invalid save identifier. Reopen the form and retry.")
        }
    }

    private fun validateFileName(fileName: String) {
        if (fileName.isBlank() || fileName.length > 180 || fileName.any { it.code < 32 || it == '/' || it == '\\' }) {
            throw LedgerException("Choose a file with a valid name of at most 180 characters.")
        }
    }

    private fun nextRevision(revision: Int): Int = try { Math.addExact(revision, 1) }
        catch (_: ArithmeticException) { throw LedgerException("This transaction cannot be edited further.") }

    private fun inputFingerprint(input: LedgerInput) = fingerprint(input.kind, input.amount, input.currency,
        input.date, input.merchant, input.category, input.notes)

    private fun fingerprint(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun <T> operation(block: () -> T): T = synchronized(operationLock) {
        try { block() }
        catch (_: SQLiteException) { throw LedgerException("The local ledger could not be saved or read. Check device storage and retry.") }
    }

    override fun close() = synchronized(operationLock) { database.close() }

    companion object {
        private val operationLock = Any()
        const val MAX_DOCUMENT_BYTES = 5 * 1024 * 1024

        fun matchesDocumentType(mimeType: String, bytes: ByteArray): Boolean {
            fun prefix(vararg expected: Int) = bytes.size >= expected.size && expected.indices.all { (bytes[it].toInt() and 255) == expected[it] }
            return when (mimeType) {
                "image/jpeg" -> prefix(255, 216, 255)
                "image/png" -> prefix(137, 80, 78, 71, 13, 10, 26, 10)
                "image/webp" -> bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                    bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
                "application/pdf" -> prefix(37, 80, 68, 70, 45)
                else -> false
            }
        }
    }
}

class LedgerHttpRemote(private val baseUrl: String = BuildConfig.API_BASE_URL) : LedgerRemote {
    override fun quote(from: String, to: String, date: String): LedgerRate {
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        val json = request("/api/ledger/exchange?from=${encode(from)}&to=${encode(to)}&date=${encode(date)}")
        return LedgerRate(json.optString("rate"), json.optString("date"), json.optString("source"))
    }

    override fun extract(fileName: String, mimeType: String, bytes: ByteArray, defaultCurrency: String): List<LedgerImportRow> {
        return extractAt("/api/ledger/imports/preview", fileName, mimeType, bytes, defaultCurrency)
    }

    override fun extractLocalStatement(fileName: String, mimeType: String, bytes: ByteArray, defaultCurrency: String): List<LedgerImportRow> =
        extractAt("/api/ledger/imports/statement-preview", fileName, mimeType, bytes, defaultCurrency)

    private fun extractAt(path: String, fileName: String, mimeType: String, bytes: ByteArray, defaultCurrency: String): List<LedgerImportRow> {
        val json = request(path, JSONObject().apply {
            put("fileName", fileName); put("mimeType", mimeType); put("dataBase64", Base64.getEncoder().encodeToString(bytes))
            put("defaultCurrency", defaultCurrency)
        }, 90_000)
        val candidates = json.optJSONArray("candidates") ?: throw LedgerException("The document service returned an invalid response.")
        val warnings = strings(json.optJSONArray("warnings"))
        if (candidates.length() == 0) throw LedgerException("No transactions were found. " +
            warnings.joinToString(" ").take(1500).ifBlank { "Try a clearer document or enter the transaction manually." })
        if (candidates.length() > LedgerDomain.MAX_IMPORT_ROWS) throw LedgerException("The document has more than 200 transactions. Split it and retry.")
        return (0 until candidates.length()).map { index ->
            val row = candidates.optJSONObject(index) ?: throw LedgerException("The document service returned an invalid row.")
            val input = row.optJSONObject("input") ?: throw LedgerException("The document service returned an invalid row.")
            LedgerImportRow(row.optString("rowId", "document-${index + 1}"), LedgerDatabase.inputFromJson(input),
                warnings = warnings + strings(row.optJSONArray("warnings")))
        }
    }

    private fun strings(values: JSONArray?): List<String> = if (values == null) emptyList() else
        (0 until minOf(values.length(), 20)).map { values.optString(it).take(500) }

    private fun request(path: String, body: JSONObject? = null, timeout: Int = 20_000): JSONObject {
        val uri = try { URI(baseUrl.trimEnd('/') + path) } catch (_: Exception) { throw LedgerException("The backend address is not configured correctly.") }
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.scheme !in listOf("https", "http") ||
            (!BuildConfig.DEBUG && uri.scheme != "https")) throw LedgerException("Configure a valid HTTPS backend address.")
        val connection = try { uri.toURL().openConnection() as HttpURLConnection }
        catch (_: Exception) { throw LedgerException("Cannot connect to the backend. Check its address and your connection.") }
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = timeout
            connection.instanceFollowRedirects = false
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val payload = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                if (status in listOf(400, 415, 422)) {
                    val bytes = connection.errorStream?.use { stream ->
                        val buffer = ByteArray(4096)
                        var size = 0
                        while (size < buffer.size) {
                            val read = stream.read(buffer, size, buffer.size - size)
                            if (read < 0) break
                            size += read
                        }
                        buffer.copyOf(size)
                    }
                    val serverMessage = try { JSONObject(bytes?.toString(Charsets.UTF_8).orEmpty()).optString("error") } catch (_: Exception) { "" }
                    if (serverMessage.isNotBlank()) throw LedgerException(serverMessage.take(1500))
                }
                val message = when (status) {
                    413 -> "This document is too large. Choose a file of 5 MB or smaller."
                    429 -> "The service is busy. Wait a little before retrying."
                    501, 503 -> "The requested service is not configured or is temporarily unavailable. Retry later; CSV and manual entry work offline."
                    400, 415, 422 -> "The service could not read this document or date. Check the file and try again."
                    else -> "The backend could not complete the request. Retry later."
                }
                throw LedgerException(message)
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > 2 * 1024 * 1024) throw LedgerException("The service response is too large.")
                    output.write(buffer, 0, read)
                }
            }
            return JSONObject(output.toString("UTF-8"))
        } catch (error: LedgerException) { throw error }
        catch (_: java.net.SocketTimeoutException) { throw LedgerException("The service took too long. Your ledger has not been changed; retry when ready.") }
        catch (_: Exception) { throw LedgerException("Cannot reach or read the backend. Check the connection and retry; saved transactions remain on this device.") }
        finally { connection.disconnect() }
    }
}
