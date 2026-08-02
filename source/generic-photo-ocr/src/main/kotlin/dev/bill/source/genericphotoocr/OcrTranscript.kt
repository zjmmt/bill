package dev.bill.source.genericphotoocr

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Versioned, bounded evidence produced by an on-device OCR engine.
 *
 * Version 2 retains only normalized integer bounds for each recognized line. The source image is
 * still ephemeral, and version 1 text-only evidence remains readable for existing RawEvents.
 */
object OcrTranscript {
    const val MEDIA_TYPE = "application/vnd.dev.bill.ocr-transcript;version=2"
    const val LEGACY_MEDIA_TYPE = "application/vnd.dev.bill.ocr-transcript;version=1"
    const val CONNECTOR_ID = "android-quick-screenshot-ocr"
    const val MAX_EVIDENCE_BYTES = 32 * 1024
    const val MAX_LINES = 128
    const val MAX_LINE_CHARS = 256
    const val COORDINATE_SCALE = 10_000

    val SUPPORTED_MEDIA_TYPES: Set<String> = setOf(MEDIA_TYPE, LEGACY_MEDIA_TYPE)

    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        init {
            require(left in 0 until COORDINATE_SCALE)
            require(top in 0 until COORDINATE_SCALE)
            require(right in 1..COORDINATE_SCALE)
            require(bottom in 1..COORDINATE_SCALE)
            require(left < right)
            require(top < bottom)
        }

        val height: Int
            get() = bottom - top
    }

    data class RecognizedLine(
        val value: String,
        val bounds: Bounds?,
    ) {
        override fun toString(): String =
            "RecognizedLine(value=<redacted>, bounds=$bounds)"
    }

    data class Line(
        val value: String,
        val startInclusive: Int,
        val endExclusive: Int,
        val bounds: Bounds?,
    ) {
        override fun toString(): String =
            "Line(value=<redacted>, startInclusive=$startInclusive, " +
                "endExclusive=$endExclusive, bounds=$bounds)"
    }

    data class Decoded(
        val text: String,
        val lines: List<Line>,
        val mediaType: String,
    ) {
        override fun toString(): String =
            "Decoded(text=<redacted>, lines=${lines.size}, mediaType=$mediaType)"
    }

    /** Encodes text-only callers as v2 without inventing layout evidence. */
    fun encode(recognizedLines: List<String>): ByteArray? = encodeSpatial(
        recognizedLines.map { line -> RecognizedLine(value = line, bounds = null) },
    )

    /**
     * Records that image capture and OCR completed but produced no usable text.
     *
     * The empty v2 envelope is evidence of an explicit local capture, not evidence of any
     * transaction field. Its parser result therefore remains an editable, blank review item.
     */
    fun encodeEmpty(): ByteArray = checkNotNull(boundedUtf8(HEADER_V2))

    fun encodeSpatial(recognizedLines: List<RecognizedLine>): ByteArray? {
        if (recognizedLines.isEmpty() || recognizedLines.size > MAX_LINES) return null
        val normalized = recognizedLines.mapNotNull { line ->
            normalizeLine(line.value)?.let { value -> line.copy(value = value) }
        }
        if (normalized.isEmpty() || normalized.size > MAX_LINES) return null

        val text = buildString {
            append(HEADER_V2)
            normalized.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(line.bounds?.toToken() ?: NO_BOUNDS_TOKEN)
                append('\t')
                append(line.value)
            }
        }
        return boundedUtf8(text)
    }

    /** Test/replay helper for pre-v2 evidence; new captures must use [encodeSpatial]. */
    fun encodeLegacy(recognizedLines: List<String>): ByteArray? {
        if (recognizedLines.isEmpty() || recognizedLines.size > MAX_LINES) return null
        val normalized = recognizedLines.mapNotNull(::normalizeLine)
        if (normalized.isEmpty() || normalized.size > MAX_LINES) return null
        val text = buildString {
            append(HEADER_V1)
            normalized.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(line)
            }
        }
        return boundedUtf8(text)
    }

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.isEmpty() || bytes.size > MAX_EVIDENCE_BYTES) return null
        val text = decodeStrictUtf8(bytes) ?: return null
        if (text.indexOf('\u0000') >= 0 || '\r' in text) return null
        return when {
            text.startsWith(HEADER_V2) -> decodeV2(text)
            text.startsWith(HEADER_V1) -> decodeV1(text)
            else -> null
        }
    }

    private fun decodeV1(text: String): Decoded? {
        val lines = decodeLines(text, HEADER_V1.length) { cursor, end ->
            val value = text.substring(cursor, end)
            if (value != normalizeLine(value)) return@decodeLines null
            Line(
                value = value,
                startInclusive = cursor,
                endExclusive = end,
                bounds = null,
            )
        } ?: return null
        return Decoded(text = text, lines = lines, mediaType = LEGACY_MEDIA_TYPE)
    }

    private fun decodeV2(text: String): Decoded? {
        if (text == HEADER_V2) {
            return Decoded(text = text, lines = emptyList(), mediaType = MEDIA_TYPE)
        }
        val lines = decodeLines(text, HEADER_V2.length) { cursor, end ->
            val separator = text.indexOf('\t', cursor)
            if (separator !in (cursor + 1) until end) return@decodeLines null
            if (text.indexOf('\t', separator + 1) in (separator + 1) until end) {
                return@decodeLines null
            }
            val bounds = parseBounds(text.substring(cursor, separator))
                ?: if (text.substring(cursor, separator) == NO_BOUNDS_TOKEN) {
                    null
                } else {
                    return@decodeLines null
                }
            val valueStart = separator + 1
            val value = text.substring(valueStart, end)
            if (value != normalizeLine(value)) return@decodeLines null
            Line(
                value = value,
                startInclusive = valueStart,
                endExclusive = end,
                bounds = bounds,
            )
        } ?: return null
        return Decoded(text = text, lines = lines, mediaType = MEDIA_TYPE)
    }

    private inline fun decodeLines(
        text: String,
        contentStart: Int,
        decodeLine: (cursor: Int, end: Int) -> Line?,
    ): List<Line>? {
        if (contentStart >= text.length) return null
        val lines = ArrayList<Line>()
        var cursor = contentStart
        while (cursor <= text.length) {
            val newline = text.indexOf('\n', cursor)
            val end = if (newline >= 0) newline else text.length
            if (end == cursor) return null
            lines += decodeLine(cursor, end) ?: return null
            if (lines.size > MAX_LINES) return null
            if (newline < 0) break
            cursor = newline + 1
            if (cursor == text.length) return null
        }
        return lines.takeIf { it.isNotEmpty() }
    }

    private fun normalizeLine(value: String): String? {
        if (value.indexOf('\u0000') >= 0 || '\n' in value || '\r' in value) return null
        val normalized = value.trim().replace(Regex("[\\t ]+"), " ")
        return normalized.takeIf { it.isNotEmpty() && it.length <= MAX_LINE_CHARS }
    }

    private fun parseBounds(token: String): Bounds? {
        if (!BOUNDS_TOKEN.matches(token)) return null
        val values = token.split(',').map { component ->
            component.toIntOrNull() ?: return null
        }
        return try {
            Bounds(
                left = values[0],
                top = values[1],
                right = values[2],
                bottom = values[3],
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun Bounds.toToken(): String = "$left,$top,$right,$bottom"

    private fun boundedUtf8(text: String): ByteArray? =
        text.toByteArray(StandardCharsets.UTF_8).takeIf { it.size <= MAX_EVIDENCE_BYTES }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private const val HEADER_V1 = "BILL-OCR/1\n"
    private const val HEADER_V2 = "BILL-OCR/2\n"
    private const val NO_BOUNDS_TOKEN = "-"
    private val BOUNDS_TOKEN = Regex("[0-9]{1,5}(?:,[0-9]{1,5}){3}")
}
