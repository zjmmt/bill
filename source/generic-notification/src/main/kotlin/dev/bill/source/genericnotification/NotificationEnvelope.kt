package dev.bill.source.genericnotification

import dev.bill.source.contract.NotificationField
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/** A bounded, redacted-in-toString copy of the notification fields allowed into evidence. */
class NotificationContent private constructor(
    private val values: Map<NotificationField, String>,
) {
    fun field(field: NotificationField): String? = values[field]

    fun presentFields(): Set<NotificationField> = values.keys.toSet()

    internal fun valueEntries(): List<Map.Entry<NotificationField, String>> =
        values.entries.sortedBy { (field) -> field.ordinal }

    override fun toString(): String = "NotificationContent(fieldCount=${values.size})"

    companion object {
        const val MAX_FIELD_CHARACTERS = 1_024

        /**
         * Returns null for empty, NUL-bearing or overly long content. Callers must not log values
         * when construction fails.
         */
        fun from(values: Map<NotificationField, String?>): NotificationContent? {
            val accepted = linkedMapOf<NotificationField, String>()
            values.forEach { (field, rawValue) ->
                if (rawValue == null || rawValue.isBlank()) return@forEach
                if (
                    rawValue.length > MAX_FIELD_CHARACTERS ||
                    rawValue.indexOf(NUL) >= 0 ||
                    rawValue.hasUnpairedSurrogate()
                ) {
                    return null
                }
                accepted[field] = rawValue
            }
            return accepted.takeIf { it.isNotEmpty() }?.let(::NotificationContent)
        }

        private const val NUL = '\u0000'

        private fun String.hasUnpairedSurrogate(): Boolean {
            var index = 0
            while (index < length) {
                when {
                    Character.isHighSurrogate(this[index]) -> {
                        if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) {
                            return true
                        }
                        index += 2
                    }

                    Character.isLowSurrogate(this[index]) -> return true
                    else -> index += 1
                }
            }
            return false
        }
    }
}

/**
 * A local-only envelope. It deliberately has no package name, notification key, actions, URI or
 * Android framework object: those values must remain at the Android capture boundary.
 */
class NotificationEnvelope(
    val templateId: String,
    val templateVersion: String,
    val postedAtEpochMillis: Long,
    val content: NotificationContent,
) {
    init {
        require(isOpaqueToken(templateId)) { "Notification template id must be an opaque token" }
        require(isOpaqueToken(templateVersion)) {
            "Notification template version must be an opaque token"
        }
        require(postedAtEpochMillis >= 0L) { "Notification post time cannot be negative" }
    }

    override fun toString(): String =
        "NotificationEnvelope(templateId=$templateId, templateVersion=$templateVersion, " +
            "fieldCount=${content.presentFields().size})"
}

object NotificationEvidenceMediaTypes {
    const val ENVELOPE = "application/vnd.dev.bill.notification-envelope"
}

sealed interface NotificationEnvelopeDecodeResult {
    data class Decoded(val envelope: NotificationEnvelope) : NotificationEnvelopeDecodeResult

    data object Malformed : NotificationEnvelopeDecodeResult
}

/**
 * Strictly encodes a small binary envelope as ASCII Base64 so it can share the existing private
 * evidence transport without making notification text human-readable in generic diagnostics.
 */
object NotificationEnvelopeCodec {
    const val MAX_ENCODED_BYTES = 8 * 1024

    private const val MAX_BINARY_BYTES = 6 * 1024
    private const val FORMAT_VERSION = 1
    private val magic = byteArrayOf('B'.code.toByte(), 'N'.code.toByte(), 'O'.code.toByte())

    fun encode(envelope: NotificationEnvelope): ByteArray? {
        var binary: ByteArray? = null
        return try {
            val encodedBinary = ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.write(magic)
                    output.writeByte(FORMAT_VERSION)
                    writeString(output, envelope.templateId)
                    writeString(output, envelope.templateVersion)
                    output.writeLong(envelope.postedAtEpochMillis)
                    val entries = envelope.content.valueEntries()
                    output.writeByte(entries.size)
                    entries.forEach { (field, value) ->
                        output.writeByte(field.ordinal)
                        writeString(output, value)
                    }
                }
                buffer.toByteArray()
            }
            binary = encodedBinary
            if (encodedBinary.size > MAX_BINARY_BYTES) return null
            Base64.getEncoder().encode(encodedBinary).takeIf { it.size <= MAX_ENCODED_BYTES }
        } catch (_: CharacterCodingException) {
            null
        } finally {
            binary?.fill(0)
        }
    }

    fun decode(encoded: ByteArray): NotificationEnvelopeDecodeResult {
        if (encoded.isEmpty() || encoded.size > MAX_ENCODED_BYTES) {
            return NotificationEnvelopeDecodeResult.Malformed
        }
        var binary: ByteArray? = null
        return try {
            binary = Base64.getDecoder().decode(encoded)
            if (binary.size > MAX_BINARY_BYTES) return NotificationEnvelopeDecodeResult.Malformed
            DataInputStream(ByteArrayInputStream(binary)).use { input ->
                val readMagic = ByteArray(magic.size)
                input.readFully(readMagic)
                if (!readMagic.contentEquals(magic) || input.readUnsignedByte() != FORMAT_VERSION) {
                    return NotificationEnvelopeDecodeResult.Malformed
                }
                val templateId = readOpaqueToken(input) ?: return NotificationEnvelopeDecodeResult.Malformed
                val templateVersion = readOpaqueToken(input)
                    ?: return NotificationEnvelopeDecodeResult.Malformed
                val postedAtEpochMillis = input.readLong().takeIf { it >= 0L }
                    ?: return NotificationEnvelopeDecodeResult.Malformed
                val count = input.readUnsignedByte()
                if (count !in 1..NotificationField.entries.size) {
                    return NotificationEnvelopeDecodeResult.Malformed
                }
                val fields = linkedMapOf<NotificationField, String?>()
                repeat(count) {
                    val ordinal = input.readUnsignedByte()
                    val field = NotificationField.entries.getOrNull(ordinal)
                        ?: return NotificationEnvelopeDecodeResult.Malformed
                    if (fields.containsKey(field)) return NotificationEnvelopeDecodeResult.Malformed
                    fields[field] = readText(input) ?: return NotificationEnvelopeDecodeResult.Malformed
                }
                if (input.available() != 0) return NotificationEnvelopeDecodeResult.Malformed
                val content = NotificationContent.from(fields)
                    ?: return NotificationEnvelopeDecodeResult.Malformed
                NotificationEnvelopeDecodeResult.Decoded(
                    NotificationEnvelope(
                        templateId = templateId,
                        templateVersion = templateVersion,
                        postedAtEpochMillis = postedAtEpochMillis,
                        content = content,
                    ),
                )
            }
        } catch (_: IllegalArgumentException) {
            NotificationEnvelopeDecodeResult.Malformed
        } catch (_: CharacterCodingException) {
            NotificationEnvelopeDecodeResult.Malformed
        } catch (_: IOException) {
            NotificationEnvelopeDecodeResult.Malformed
        } catch (_: RuntimeException) {
            NotificationEnvelopeDecodeResult.Malformed
        } finally {
            binary?.fill(0)
        }
    }

    @Throws(CharacterCodingException::class)
    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = encodeUtf8(value)
        try {
            if (bytes.isEmpty() || bytes.size > MAX_BINARY_BYTES) {
                throw CharacterCodingException()
            }
            output.writeInt(bytes.size)
            output.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    @Throws(CharacterCodingException::class)
    private fun readOpaqueToken(input: DataInputStream): String? =
        readText(input)?.takeIf(::isOpaqueToken)

    @Throws(CharacterCodingException::class)
    private fun readText(input: DataInputStream): String? {
        val length = input.readInt()
        if (length !in 1..MAX_BINARY_BYTES || length > input.available()) return null
        val bytes = ByteArray(length)
        return try {
            input.readFully(bytes)
            decodeUtf8(bytes).takeIf {
                it.length <= NotificationContent.MAX_FIELD_CHARACTERS && it.indexOf('\u0000') < 0
            }
        } finally {
            bytes.fill(0)
        }
    }

    @Throws(CharacterCodingException::class)
    private fun encodeUtf8(value: String): ByteArray {
        val buffer = StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    @Throws(CharacterCodingException::class)
    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}

private fun isOpaqueToken(value: String): Boolean =
    value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
