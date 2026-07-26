package dev.bill.source.contract

import java.util.Locale

/**
 * Narrow transport allowlist for a receipt image the user explicitly shares to Bill.
 *
 * This identifies neither a provider nor a transaction. The current first slice accepts only
 * PNG screenshots so the app can enforce a small, predictable local evidence budget without
 * requesting broad media access or decoding arbitrary gallery images.
 */
object ImageEvidenceMediaTypes {
    const val PNG = "image/png"
    const val IMAGE_WILDCARD = "image/*"

    val SHARED_RECEIPT_IMAGE: Set<String> = setOf(PNG)

    fun canonicalize(mediaType: String): String = mediaType
        .substringBefore(';')
        .trim()
        .lowercase(Locale.ROOT)
}
