package com.example.cpen321application

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The guest ledger is scoped to this app installation. Raw uploaded documents are never stored. */
class LedgerDatabase(context: Context, name: String = "ledger_guest.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ledger_records (id TEXT PRIMARY KEY, revision INTEGER NOT NULL, data TEXT NOT NULL, date TEXT NOT NULL, created_at TEXT NOT NULL)")
        db.execSQL("CREATE INDEX ledger_records_date ON ledger_records(date DESC, created_at DESC)")
        db.execSQL("CREATE TABLE ledger_requests (request_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, result TEXT NOT NULL)")
        db.execSQL("CREATE TABLE ledger_preferences (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE ledger_rates (cache_key TEXT PRIMARY KEY, rate TEXT NOT NULL, rate_date TEXT NOT NULL, source TEXT NOT NULL, fetched_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first ledger schema. Future upgrades must migrate, never discard entries.
        throw IllegalStateException("Unsupported ledger schema upgrade.")
    }

    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    fun load(): List<LedgerRecord> = readableDatabase.query(
        "ledger_records", arrayOf("data"), null, null, null, null, "date DESC, created_at DESC, id ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(recordFromJson(JSONObject(cursor.getString(0)))) } }

    fun find(id: String): LedgerRecord? = readableDatabase.query(
        "ledger_records", arrayOf("data"), "id = ?", arrayOf(id), null, null, null
    ).use { if (it.moveToFirst()) recordFromJson(JSONObject(it.getString(0))) else null }

    fun insert(record: LedgerRecord) {
        writableDatabase.insertOrThrow("ledger_records", null, recordValues(record))
    }

    fun update(record: LedgerRecord, expectedRevision: Int): Boolean = writableDatabase.update(
        "ledger_records", recordValues(record), "id = ? AND revision = ?", arrayOf(record.id, expectedRevision.toString())
    ) == 1

    fun delete(id: String, revision: Int): Boolean = writableDatabase.delete(
        "ledger_records", "id = ? AND revision = ?", arrayOf(id, revision.toString())
    ) == 1

    private fun recordValues(record: LedgerRecord) = ContentValues().apply {
        put("id", record.id)
        put("revision", record.revision)
        put("data", recordToJson(record).toString())
        put("date", record.input.date)
        put("created_at", record.createdAt)
    }

    fun request(requestId: String): Pair<String, String>? = readableDatabase.query(
        "ledger_requests", arrayOf("fingerprint", "result"), "request_id = ?", arrayOf(requestId), null, null, null
    ).use { if (it.moveToFirst()) Pair(it.getString(0), it.getString(1)) else null }

    fun rememberRequest(requestId: String, fingerprint: String, result: String) {
        writableDatabase.insertOrThrow("ledger_requests", null, ContentValues().apply {
            put("request_id", requestId); put("fingerprint", fingerprint); put("result", result)
        })
    }

    fun baseCurrency(): String = readableDatabase.query(
        "ledger_preferences", arrayOf("value"), "key = ?", arrayOf("baseCurrency"), null, null, null
    ).use { if (it.moveToFirst()) it.getString(0) else "CAD" }

    fun setBaseCurrency(currency: String) {
        val inserted = writableDatabase.insertWithOnConflict("ledger_preferences", null, ContentValues().apply {
            put("key", "baseCurrency"); put("value", currency)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (inserted == -1L) throw SQLiteException("Could not persist reporting currency.")
    }

    fun cachedRate(from: String, to: String, requestedDate: String): LedgerRate? = readableDatabase.query(
        "ledger_rates", arrayOf("rate", "rate_date", "source", "fetched_at"), "cache_key = ?",
        arrayOf("$from|$to|$requestedDate"), null, null, null
    ).use {
        if (!it.moveToFirst()) null
        // A quote first requested on that same day is provisional until the day's ECB
        // rate is published. Historical lookups remain cached across app restarts.
        else if (Instant.ofEpochMilli(it.getLong(3)).atZone(ZoneOffset.UTC).toLocalDate() == LocalDate.parse(requestedDate) &&
            System.currentTimeMillis() - it.getLong(3) > 300_000L) null
        else LedgerRate(it.getString(0), it.getString(1), it.getString(2))
    }

    fun cacheRate(from: String, to: String, requestedDate: String, rate: LedgerRate) {
        val inserted = writableDatabase.insertWithOnConflict("ledger_rates", null, ContentValues().apply {
            put("cache_key", "$from|$to|$requestedDate"); put("rate", rate.rate)
            put("rate_date", rate.date); put("source", rate.source); put("fetched_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (inserted == -1L) throw SQLiteException("Could not persist the exchange rate.")
    }

    companion object {
        fun inputToJson(input: LedgerInput): JSONObject = JSONObject().apply {
            put("kind", input.kind); put("amount", input.amount); put("currency", input.currency)
            put("date", input.date); put("merchant", input.merchant); put("category", input.category); put("notes", input.notes)
        }

        fun inputFromJson(json: JSONObject): LedgerInput = LedgerInput(
            json.optString("kind", "expense"), json.optString("amount", ""), json.optString("currency", "CAD"),
            json.optString("date", ""), json.optString("merchant", ""), json.optString("category", "Other"), json.optString("notes", "")
        )

        fun recordToJson(record: LedgerRecord): JSONObject = JSONObject().apply {
            put("id", record.id); put("input", inputToJson(record.input)); put("amountMinor", record.amountMinor)
            put("fx", JSONObject().apply {
                put("baseCurrency", record.fx.baseCurrency); put("rate", record.fx.rate); put("rateDate", record.fx.rateDate)
                put("source", record.fx.source); put("convertedMinor", record.fx.convertedMinor)
            })
            put("source", record.source); put("sourceFileName", record.sourceFileName); put("revision", record.revision)
            put("createdAt", record.createdAt); put("updatedAt", record.updatedAt)
        }

        fun recordFromJson(json: JSONObject): LedgerRecord {
            val fx = json.getJSONObject("fx")
            return LedgerRecord(json.getString("id"), inputFromJson(json.getJSONObject("input")), json.getLong("amountMinor"),
                LedgerFx(fx.getString("baseCurrency"), fx.getString("rate"), fx.getString("rateDate"), fx.getString("source"), fx.getLong("convertedMinor")),
                json.getString("source"), json.optString("sourceFileName", ""), json.getInt("revision"),
                json.getString("createdAt"), json.getString("updatedAt"))
        }
    }
}
