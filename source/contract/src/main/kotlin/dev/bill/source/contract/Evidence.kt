package dev.bill.source.contract

import java.security.MessageDigest

@JvmInline
value class EvidenceHash(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Evidence hash must be a lowercase SHA-256 value"
        }
    }

    companion object {
        fun fromBytes(bytes: ByteArray): EvidenceHash {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return EvidenceHash(digest.joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            })
        }
    }
}

sealed interface EvidenceLocator {
    data object WholePayload : EvidenceLocator

    data class ByteRange(
        val startInclusive: Long,
        val endExclusive: Long,
    ) : EvidenceLocator {
        init {
            require(startInclusive >= 0) { "Byte range start cannot be negative" }
            require(endExclusive > startInclusive) { "Byte range must be non-empty" }
        }
    }

    data class TextRange(
        val startInclusive: Int,
        val endExclusive: Int,
    ) : EvidenceLocator {
        init {
            require(startInclusive >= 0) { "Text range start cannot be negative" }
            require(endExclusive > startInclusive) { "Text range must be non-empty" }
        }
    }

    data class TableCell(
        val rowIndex: Int,
        val columnIndex: Int,
    ) : EvidenceLocator {
        init {
            require(rowIndex >= 0) { "Table row index cannot be negative" }
            require(columnIndex >= 0) { "Table column index cannot be negative" }
        }
    }

    data class NotificationFieldLocator(
        val field: NotificationField,
    ) : EvidenceLocator
}

enum class NotificationField {
    TITLE,
    TEXT,
    SUB_TEXT,
    BIG_TEXT,
    SUMMARY_TEXT,
}

private val mediaTypePattern = Regex(
    "[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:;[ -~]+)?",
)

/** A defensive in-memory view of evidence; content is never included in diagnostics. */
class EvidenceInput(
    val mediaType: String,
    bytes: ByteArray,
) {
    private val content = bytes.copyOf()

    val sizeBytes: Long = content.size.toLong()

    init {
        require(mediaType.length <= 255 && mediaTypePattern.matches(mediaType)) {
            "Media type must be a valid bounded ASCII value"
        }
    }

    fun copyBytes(): ByteArray = content.copyOf()

    override fun toString(): String = "EvidenceInput(sizeBytes=$sizeBytes)"
}
