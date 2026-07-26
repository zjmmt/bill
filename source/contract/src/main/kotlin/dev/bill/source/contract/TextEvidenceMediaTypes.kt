package dev.bill.source.contract

import java.util.Locale

/**
 * Narrow media-type allowlists for generic user-provided text evidence.
 *
 * These values describe transport only. They do not identify a payment provider or a statement
 * format, and every accepted payload is still validated as bounded UTF-8 before persistence.
 */
object TextEvidenceMediaTypes {
    const val TEXT_PLAIN = "text/plain"
    const val TEXT_CSV = "text/csv"
    const val TEXT_TAB_SEPARATED = "text/tab-separated-values"
    const val APPLICATION_CSV = "application/csv"

    val SHARED_TEXT: Set<String> = setOf(TEXT_PLAIN)

    val USER_SELECTED_TEXT_FILE: Set<String> = setOf(
        TEXT_PLAIN,
        TEXT_CSV,
        TEXT_TAB_SEPARATED,
        APPLICATION_CSV,
    )

    fun canonicalize(mediaType: String): String = mediaType
        .substringBefore(';')
        .trim()
        .lowercase(Locale.ROOT)
}
