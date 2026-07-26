package dev.bill.source.genericphotoocr

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Versioned, bounded evidence produced by an on-device OCR engine.
 *
 * The image is an ephemeral input. Only this transcript enters the immutable source evidence
 * chain, so no decoder- or provider-specific object crosses into the pure source layer.
 */
object OcrTranscript {
    const val MEDIA_TYPE = "application/vnd.dev.bill.ocr-transcript;version=1"
    const val CONNECTOR_ID = "android-quick-screenshot-ocr"
    const val MAX_EVIDENCE_BYTES = 32 * 1024
    const val MAX_LINES = 128
    const val MAX_LINE_CHARS = 256
    private const val HEADER = "BILL-OCR/1\n"

    data class Line(
        val value: String,
        val startInclusive: Int,
        val endExclusive: Int,
    )

    data class Decoded(
        val text: String,
        val lines: List<Line>,
    )

    fun encode(recognizedLines: List<String>): ByteArray? {
        if (recognizedLines.isEmpty() || recognizedLines.size > MAX_LINES) return null
        val normalized = recognizedLines.mapNotNull(::normalizeLine)
        if (normalized.isEmpty() || normalized.size > MAX_LINES) return null

        val text = buildString {
            append(HEADER)
            normalized.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(line)
            }
        }
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        return bytes.takeIf { it.size <= MAX_EVIDENCE_BYTES }
    }

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.isEmpty() || bytes.size > MAX_EVIDENCE_BYTES) return null
        val text = decodeStrictUtf8(bytes) ?: return null
        if (!text.startsWith(HEADER) || text.indexOf('\u0000') >= 0 || '\r' in text) return null

        val contentStart = HEADER.length
        if (contentStart >= text.length) return null
        val lines = ArrayList<Line>()
        var cursor = contentStart
        while (cursor <= text.length) {
            val newline = text.indexOf('\n', cursor)
            val end = if (newline >= 0) newline else text.length
            if (end == cursor) return null
            val value = text.substring(cursor, end)
            if (value.length > MAX_LINE_CHARS || value != normalizeLine(value)) return null
            lines += Line(value, cursor, end)
            if (lines.size > MAX_LINES) return null
            if (newline < 0) break
            cursor = newline + 1
            if (cursor == text.length) return null
        }
        return Decoded(text = text, lines = lines).takeIf { lines.isNotEmpty() }
    }

    private fun normalizeLine(value: String): String? {
        if (value.indexOf('\u0000') >= 0 || '\n' in value || '\r' in value) return null
        val normalized = value.trim().replace(Regex("[\\t ]+"), " ")
        return normalized.takeIf { it.isNotEmpty() && it.length <= MAX_LINE_CHARS }
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
}
