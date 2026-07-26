package dev.bill.data.local

import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.Money
import dev.bill.source.contract.DateTimePrecision
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceLocator
import dev.bill.source.contract.FieldCandidate
import dev.bill.source.contract.NormalizedCandidate
import dev.bill.source.contract.NotificationField
import dev.bill.source.contract.ObservedMoneyDirection
import dev.bill.source.contract.ObservedTime
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.ScopedExternalReference
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TreeSet

internal object SourceCandidateCodec {
    fun encode(candidate: NormalizedCandidate?): ByteArray? {
        if (candidate == null) return null
        return safely {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                output.writeInt(Magic)
                output.writeInt(Version)
                output.writeOptional(candidate.amount) { field ->
                    output.writeLong(field.value.minorUnits)
                    output.writeString(field.value.currency.value)
                    output.writeCandidateMetadata(field)
                }
                output.writeOptional(candidate.moneyDirection) { field ->
                    output.writeString(field.value.name)
                    output.writeCandidateMetadata(field)
                }
                output.writeOptional(candidate.occurredAt) { field ->
                    when (val value = field.value) {
                        is ObservedTime.DateOnly -> {
                            output.writeByte(ObservedDateOnly)
                            output.writeString(value.date.toString())
                        }

                        is ObservedTime.DateTime -> {
                            output.writeByte(ObservedDateTime)
                            output.writeString(value.localDateTime.toString())
                            output.writeString(value.precision.name)
                            output.writeNullableString(value.offset?.id)
                            output.writeNullableString(value.zoneId?.id)
                            output.writeNullableInstant(value.resolvedInstant)
                        }
                    }
                    output.writeCandidateMetadata(field)
                }
                output.writeOptional(candidate.counterparty) { field ->
                    output.writeString(field.value)
                    output.writeCandidateMetadata(field)
                }
                output.writeOptional(candidate.fundingHint) { field ->
                    output.writeString(field.value)
                    output.writeCandidateMetadata(field)
                }
                val references = TreeSet(compareBy<ScopedExternalReference>(
                    { it.providerId.value },
                    { it.accountScopeHash.value },
                    { it.referenceType },
                    { it.valueHash.value },
                )).apply { addAll(candidate.externalReferences) }
                require(references.size <= MaxReferences)
                output.writeInt(references.size)
                references.forEach { reference ->
                    output.writeString(reference.providerId.value)
                    output.writeString(reference.accountScopeHash.value)
                    output.writeString(reference.referenceType)
                    output.writeString(reference.valueHash.value)
                }
            }
            buffer.toByteArray().also { require(it.size <= MaxPayloadBytes) }
        }
    }

    fun decode(payload: ByteArray?): NormalizedCandidate? {
        if (payload == null) return null
        return safely {
            require(payload.size <= MaxPayloadBytes)
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == Magic)
                require(input.readInt() == Version)
                val amount = input.readOptional {
                    FieldCandidate(
                        value = Money(
                            minorUnits = input.readLong(),
                            currency = CurrencyCode(input.readString()),
                        ),
                        confidence = input.readDouble(),
                        evidenceLocator = input.readLocator(),
                    )
                }
                val direction = input.readOptional {
                    FieldCandidate(
                        value = enumValueOf<ObservedMoneyDirection>(input.readString()),
                        confidence = input.readDouble(),
                        evidenceLocator = input.readLocator(),
                    )
                }
                val occurredAt = input.readOptional {
                    val value = when (input.readByte().toInt()) {
                        ObservedDateOnly -> ObservedTime.DateOnly(
                            LocalDate.parse(input.readString()),
                        )

                        ObservedDateTime -> ObservedTime.DateTime(
                            localDateTime = LocalDateTime.parse(input.readString()),
                            precision = enumValueOf<DateTimePrecision>(input.readString()),
                            offset = input.readNullableString()?.let(ZoneOffset::of),
                            zoneId = input.readNullableString()?.let(ZoneId::of),
                            resolvedInstant = input.readNullableInstant(),
                        )

                        else -> throw CandidateCodecException()
                    }
                    FieldCandidate(
                        value = value,
                        confidence = input.readDouble(),
                        evidenceLocator = input.readLocator(),
                    )
                }
                val counterparty = input.readOptional {
                    FieldCandidate(
                        value = input.readString(),
                        confidence = input.readDouble(),
                        evidenceLocator = input.readLocator(),
                    )
                }
                val fundingHint = input.readOptional {
                    FieldCandidate(
                        value = input.readString(),
                        confidence = input.readDouble(),
                        evidenceLocator = input.readLocator(),
                    )
                }
                val referenceCount = input.readInt()
                require(referenceCount in 0..MaxReferences)
                val references = buildSet {
                    repeat(referenceCount) {
                        add(
                            ScopedExternalReference(
                                providerId = ProviderId(input.readString()),
                                accountScopeHash = EvidenceHash(input.readString()),
                                referenceType = input.readString(),
                                valueHash = EvidenceHash(input.readString()),
                            ),
                        )
                    }
                }
                require(input.read() == -1)
                NormalizedCandidate(
                    amount = amount,
                    moneyDirection = direction,
                    occurredAt = occurredAt,
                    counterparty = counterparty,
                    fundingHint = fundingHint,
                    externalReferences = references,
                )
            }
        }
    }

    private fun DataOutputStream.writeCandidateMetadata(field: FieldCandidate<*>) {
        writeDouble(field.confidence)
        writeLocator(field.evidenceLocator)
    }

    private fun DataOutputStream.writeLocator(locator: EvidenceLocator) {
        when (locator) {
            EvidenceLocator.WholePayload -> writeByte(LocatorWhole)
            is EvidenceLocator.ByteRange -> {
                writeByte(LocatorByteRange)
                writeLong(locator.startInclusive)
                writeLong(locator.endExclusive)
            }

            is EvidenceLocator.TextRange -> {
                writeByte(LocatorTextRange)
                writeInt(locator.startInclusive)
                writeInt(locator.endExclusive)
            }

            is EvidenceLocator.TableCell -> {
                writeByte(LocatorTableCell)
                writeInt(locator.rowIndex)
                writeInt(locator.columnIndex)
            }

            is EvidenceLocator.NotificationFieldLocator -> {
                writeByte(LocatorNotificationField)
                writeString(locator.field.name)
            }
        }
    }

    private fun DataInputStream.readLocator(): EvidenceLocator = when (readByte().toInt()) {
        LocatorWhole -> EvidenceLocator.WholePayload
        LocatorByteRange -> EvidenceLocator.ByteRange(readLong(), readLong())
        LocatorTextRange -> EvidenceLocator.TextRange(readInt(), readInt())
        LocatorTableCell -> EvidenceLocator.TableCell(readInt(), readInt())
        LocatorNotificationField -> EvidenceLocator.NotificationFieldLocator(
            enumValueOf<NotificationField>(readString()),
        )

        else -> throw CandidateCodecException()
    }

    private inline fun <T> DataOutputStream.writeOptional(
        value: T?,
        writeValue: (T) -> Unit,
    ) {
        writeBoolean(value != null)
        if (value != null) writeValue(value)
    }

    private inline fun <T> DataInputStream.readOptional(readValue: () -> T): T? =
        if (readBoolean()) readValue() else null

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MaxStringBytes)
        writeInt(bytes.size)
        write(bytes)
        bytes.fill(0)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MaxStringBytes)
        val bytes = ByteArray(size)
        readFully(bytes)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataOutputStream.writeNullableString(value: String?) =
        writeOptional(value) { string -> writeString(string) }

    private fun DataInputStream.readNullableString(): String? = readOptional { readString() }

    private fun DataOutputStream.writeNullableInstant(value: Instant?) {
        writeBoolean(value != null)
        if (value != null) {
            writeLong(value.epochSecond)
            writeInt(value.nano)
        }
    }

    private fun DataInputStream.readNullableInstant(): Instant? =
        if (readBoolean()) Instant.ofEpochSecond(readLong(), readInt().toLong()) else null

    private inline fun <T> safely(operation: () -> T): T = try {
        operation()
    } catch (_: CandidateCodecException) {
        throw CandidateCodecException()
    } catch (_: Exception) {
        throw CandidateCodecException()
    }

    private const val Magic = 0x42494C4C
    private const val Version = 1
    private const val MaxPayloadBytes = 1024 * 1024
    private const val MaxStringBytes = 256 * 1024
    private const val MaxReferences = 1024
    private const val ObservedDateOnly = 1
    private const val ObservedDateTime = 2
    private const val LocatorWhole = 1
    private const val LocatorByteRange = 2
    private const val LocatorTextRange = 3
    private const val LocatorTableCell = 4
    private const val LocatorNotificationField = 5
}

internal class CandidateCodecException :
    IllegalArgumentException("Source candidate payload failed validation")
