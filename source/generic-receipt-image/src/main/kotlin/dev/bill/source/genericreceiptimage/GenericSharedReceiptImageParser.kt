package dev.bill.source.genericreceiptimage

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.ImageEvidenceMediaTypes
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.ParserId
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.SafeDiagnostic
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.SourceParser
import dev.bill.source.contract.VersionId
import java.util.zip.CRC32

/**
 * Validates a single, explicitly shared PNG receipt screenshot without OCR or image decoding.
 *
 * The parser never infers a provider, amount, direction, or wallet balance. A valid image only
 * creates a user-completed review item; malformed images fail before any financial field exists.
 */
class GenericSharedReceiptImageParser : SourceParser {
    override val identity: SourceIdentity = SourceIdentity(
        parserId = ParserId("generic-shared-receipt-image"),
        providerId = ProviderId("user-shared-receipt-image"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId("android-share-receipt-image"),
        capabilities = emptySet(),
        supportedCaptureMethods = setOf(CaptureMethod.SHARE_FILE),
        parserVersion = VersionId("parser-1"),
        ruleVersion = VersionId("rules-1"),
    )

    override fun parse(rawEvent: RawEvent, evidenceInput: EvidenceInput): ParseResult {
        if (!identity.accepts(rawEvent)) return rejected(DiagnosticCode.SOURCE_NOT_ACCEPTED)
        if (evidenceInput.mediaType !in ImageEvidenceMediaTypes.SHARED_RECEIPT_IMAGE) {
            return rejected(DiagnosticCode.UNSUPPORTED_MEDIA_TYPE)
        }
        if (evidenceInput.sizeBytes > PngReceiptImageValidator.MAX_PNG_BYTES) {
            return rejected(DiagnosticCode.EVIDENCE_TOO_LARGE)
        }
        val bytes = evidenceInput.copyBytes()
        val valid = try {
            PngReceiptImageValidator.isAcceptableReceiptScreenshot(bytes)
        } finally {
            bytes.fill(0)
        }
        if (!valid) return rejected(DiagnosticCode.MALFORMED_EVIDENCE)

        return ParseResult.NeedsUserReview(
            candidate = null,
            diagnostic = SafeDiagnostic(
                code = DiagnosticCode.INSUFFICIENT_FIELDS,
                recoverable = true,
            ),
        )
    }

    private fun rejected(code: DiagnosticCode): ParseResult.Rejected = ParseResult.Rejected(
        SafeDiagnostic(code = code, recoverable = false),
    )
}

/**
 * A structural PNG envelope check. It intentionally does not decode pixels or inspect text
 * chunks; that keeps this user-triggered intake bounded and avoids an OCR/image-rendering surface
 * in the first receipt-share slice. The limits admit ordinary phone screenshots but reject
 * dimensions that would be unsafe for any future preview or OCR decoder.
 */
object PngReceiptImageValidator {
    const val MAX_PNG_BYTES: Long = 4L * 1024L * 1024L
    private const val MaxWidth = 4_096L
    private const val MaxHeight = 8_192L
    private const val MaxPixels = 24_000_000L
    private const val IhdrDataSize = 13
    private const val ChunkPrefixSize = 8
    private const val ChunkCrcSize = 4
    private const val MinimumPngSize = 58L
    private val Signature = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4e,
        0x47,
        0x0d,
        0x0a,
        0x1a,
        0x0a,
    )

    fun isAcceptableReceiptScreenshot(bytes: ByteArray): Boolean {
        if (bytes.size.toLong() !in MinimumPngSize..MAX_PNG_BYTES) return false
        if (!hasSignature(bytes)) return false

        var cursor = Signature.size
        var sawHeader = false
        var sawImageData = false
        while (cursor < bytes.size) {
            if (bytes.size - cursor < ChunkPrefixSize + ChunkCrcSize) return false
            val dataSize = readUnsignedInt(bytes, cursor)
            val remaining = (bytes.size - cursor - ChunkPrefixSize - ChunkCrcSize).toLong()
            if (dataSize > remaining) return false

            val typeOffset = cursor + 4
            val dataOffset = cursor + ChunkPrefixSize
            val crcOffset = dataOffset + dataSize.toInt()
            val nextChunkOffset = crcOffset + ChunkCrcSize
            if (!hasMatchingCrc(bytes, typeOffset, dataOffset, dataSize.toInt(), crcOffset)) {
                return false
            }

            when {
                !sawHeader -> {
                    if (!isChunkType(bytes, typeOffset, 'I', 'H', 'D', 'R') ||
                        dataSize != IhdrDataSize.toLong() ||
                        !hasValidHeader(bytes, dataOffset)
                    ) {
                        return false
                    }
                    sawHeader = true
                }

                isChunkType(bytes, typeOffset, 'I', 'D', 'A', 'T') -> {
                    if (dataSize == 0L) return false
                    sawImageData = true
                }

                isChunkType(bytes, typeOffset, 'I', 'E', 'N', 'D') -> {
                    return dataSize == 0L && sawImageData && nextChunkOffset == bytes.size
                }

                isChunkType(bytes, typeOffset, 'I', 'H', 'D', 'R') -> return false
            }
            cursor = nextChunkOffset
        }
        return false
    }

    private fun hasSignature(bytes: ByteArray): Boolean {
        for (index in Signature.indices) {
            if (bytes[index] != Signature[index]) return false
        }
        return true
    }

    private fun hasValidHeader(bytes: ByteArray, offset: Int): Boolean {
        val width = readUnsignedInt(bytes, offset)
        val height = readUnsignedInt(bytes, offset + 4)
        if (width !in 1L..MaxWidth || height !in 1L..MaxHeight) return false
        if (width > MaxPixels / height) return false

        val bitDepth = bytes[offset + 8].toInt() and 0xff
        val colorType = bytes[offset + 9].toInt() and 0xff
        if (!isSupportedColorDepth(bitDepth, colorType)) return false
        return bytes[offset + 10] == 0.toByte() &&
            bytes[offset + 11] == 0.toByte() &&
            (bytes[offset + 12].toInt() and 0xff) in 0..1
    }

    private fun isSupportedColorDepth(bitDepth: Int, colorType: Int): Boolean = when (colorType) {
        0 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16
        2, 4, 6 -> bitDepth == 8 || bitDepth == 16
        3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8
        else -> false
    }

    private fun isChunkType(
        bytes: ByteArray,
        offset: Int,
        first: Char,
        second: Char,
        third: Char,
        fourth: Char,
    ): Boolean =
        bytes[offset] == first.code.toByte() &&
            bytes[offset + 1] == second.code.toByte() &&
            bytes[offset + 2] == third.code.toByte() &&
            bytes[offset + 3] == fourth.code.toByte()

    private fun hasMatchingCrc(
        bytes: ByteArray,
        typeOffset: Int,
        dataOffset: Int,
        dataSize: Int,
        crcOffset: Int,
    ): Boolean {
        val crc = CRC32()
        crc.update(bytes, typeOffset, 4)
        crc.update(bytes, dataOffset, dataSize)
        return crc.value == readUnsignedInt(bytes, crcOffset)
    }

    private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)
}
