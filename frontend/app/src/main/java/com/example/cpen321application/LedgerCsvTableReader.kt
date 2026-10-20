package com.example.cpen321application

internal class LedgerCsvTableReader(private val text: String) {
    private val result = mutableListOf<List<String>>()
    private val row = mutableListOf<String>()
    private val field = StringBuilder()
    private var quoted = false
    private var closedQuote = false
    private var index = 0

    fun read(): List<List<String>> {
        while (index < text.length) {
            val char = text[index]
            if (quoted) readQuoted(char) else readUnquoted(char)
            if (field.length > 10_000) throw LedgerException("A CSV field is too long.")
            index++
        }
        if (quoted) throw LedgerException("CSV has an unclosed quoted field.")
        if (field.isNotEmpty() || row.isNotEmpty() || closedQuote) endRow()
        return result
    }

    private fun readQuoted(char: Char) {
        when (char) {
            '"' -> if (text.getOrNull(index + 1) == '"') {
                field.append('"')
                index++
            } else {
                quoted = false
                closedQuote = true
            }
            '\r' -> {
                field.append('\n')
                skipLineFeed()
            }
            else -> field.append(char)
        }
    }

    private fun readUnquoted(char: Char) {
        when (char) {
            '"' -> if (field.isEmpty() && !closedQuote) quoted = true
                else throw LedgerException("CSV has a misplaced quote.")
            ',' -> endField()
            '\n', '\r' -> {
                endRow()
                if (char == '\r') skipLineFeed()
            }
            else -> if (closedQuote && !char.isWhitespace()) {
                throw LedgerException("CSV has text after a closing quote.")
            } else if (!closedQuote) field.append(char)
        }
    }

    private fun skipLineFeed() {
        if (text.getOrNull(index + 1) == '\n') index++
    }

    private fun endField() {
        row.add(field.toString())
        field.setLength(0)
        closedQuote = false
        if (row.size > 64) throw LedgerException("CSV has too many columns (maximum 64).")
    }

    private fun endRow() {
        endField()
        if (!(row.size == 1 && row[0].isBlank())) result.add(row.toList())
        row.clear()
        if (result.size > LedgerDomain.MAX_IMPORT_ROWS + 1) {
            throw LedgerException("Import at most 200 transactions at a time.")
        }
    }
}
