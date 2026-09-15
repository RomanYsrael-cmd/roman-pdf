package com.romanysrael.romanpdf.data

object SearchQuery {
    /** Turns ordinary user text into a conservative prefix query for SQLite FTS. */
    fun toMatchQuery(input: String): String = input
        .trim()
        .split(Regex("\\s+"))
        .mapNotNull { token ->
            val clean = token.filter { it.isLetterOrDigit() || it == '_' }
            clean.takeIf { it.isNotEmpty() }?.let { "$it*" }
        }
        .joinToString(" AND ")
}
