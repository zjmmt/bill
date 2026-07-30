package dev.bill.source.genericdelimited

import dev.bill.core.model.Money
import dev.bill.core.model.toLongExactCompat
import dev.bill.source.contract.DateTimePrecision
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceLocator
import dev.bill.source.contract.FieldCandidate
import dev.bill.source.contract.GenericDelimitedStatementIdentity
import dev.bill.source.contract.NormalizedCandidate
import dev.bill.source.contract.ObservedMoneyDirection
import dev.bill.source.contract.ObservedTime
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.ScopedExternalReference
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

data class MappedStatementRow(
    val candidate: NormalizedCandidate,
)

sealed interface StatementRowMappingResult {
    data class Mapped(val value: MappedStatementRow) : StatementRowMappingResult

    data class Invalid(val error: StatementMappingError) : StatementRowMappingResult
}

object DelimitedStatementMapper {
    fun map(
        fileHash: EvidenceHash,
        mapping: DelimitedStatementMapping,
        row: DelimitedRow,
    ): StatementRowMappingResult {
        val requiredColumns = buildList {
            add(mapping.dateColumnIndex)
            add(mapping.amountColumnIndex)
            add(mapping.counterpartyColumnIndex)
            mapping.referenceColumnIndex?.let(::add)
            (mapping.directionMapping as? StatementDirectionMapping.DirectionColumn)
                ?.columnIndex
                ?.let(::add)
        }
        if (requiredColumns.any { it !in row.cells.indices }) {
            return invalid(StatementMappingError.COLUMN_OUT_OF_RANGE)
        }

        val observedTime = parseObservedTime(
            value = row.cells[mapping.dateColumnIndex],
            format = mapping.dateFormat,
        ) ?: return invalid(
            if (row.cells[mapping.dateColumnIndex].isBlank()) {
                StatementMappingError.EMPTY_DATE
            } else {
                StatementMappingError.INVALID_DATE
            },
        )

        val rawAmount = row.cells[mapping.amountColumnIndex].trim()
        if (rawAmount.isEmpty()) return invalid(StatementMappingError.EMPTY_AMOUNT)
        val decimal = parseAmount(rawAmount, mapping.amountFormat)
            ?: return invalid(StatementMappingError.INVALID_AMOUNT)
        if (decimal.signum() == 0) return invalid(StatementMappingError.ZERO_AMOUNT)

        val direction = when (val directionMapping = mapping.directionMapping) {
            is StatementDirectionMapping.SignedAmount -> {
                if (decimal.signum() > 0) {
                    directionMapping.positiveDirection
                } else {
                    directionMapping.positiveDirection.opposite()
                }
            }

            is StatementDirectionMapping.DirectionColumn -> {
                if (decimal.signum() < 0) {
                    return invalid(StatementMappingError.SIGNED_AMOUNT_WITH_DIRECTION_COLUMN)
                }
                val rawDirection = row.cells[directionMapping.columnIndex]
                if (rawDirection.isBlank()) {
                    return invalid(StatementMappingError.EMPTY_DIRECTION)
                }
                val normalized = normalizeDirectionToken(
                    rawDirection,
                    directionMapping.caseSensitive,
                )
                val inbound = directionMapping.inboundTokens.mapTo(mutableSetOf()) {
                    normalizeDirectionToken(it, directionMapping.caseSensitive)
                }
                val outbound = directionMapping.outboundTokens.mapTo(mutableSetOf()) {
                    normalizeDirectionToken(it, directionMapping.caseSensitive)
                }
                when (normalized) {
                    in inbound -> StatementDirection.INBOUND
                    in outbound -> StatementDirection.OUTBOUND
                    else -> return invalid(StatementMappingError.UNKNOWN_DIRECTION)
                }
            }
        }

        val minorUnits = try {
            decimal.abs()
                .setScale(MINOR_UNIT_SCALE, RoundingMode.UNNECESSARY)
                .movePointRight(MINOR_UNIT_SCALE)
                .toLongExactCompat()
        } catch (_: ArithmeticException) {
            return invalid(StatementMappingError.AMOUNT_OUT_OF_RANGE)
        }
        if (minorUnits <= 0L) return invalid(StatementMappingError.AMOUNT_OUT_OF_RANGE)

        val counterparty = row.cells[mapping.counterpartyColumnIndex].trim()
        if (counterparty.isEmpty()) return invalid(StatementMappingError.EMPTY_COUNTERPARTY)
        if (counterparty.length > MAX_COUNTERPARTY_CHARS) {
            return invalid(StatementMappingError.COUNTERPARTY_TOO_LONG)
        }

        val externalReferences = mapping.referenceColumnIndex
            ?.let(row.cells::get)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { reference ->
                if (reference.length > MAX_REFERENCE_CHARS) {
                    return invalid(StatementMappingError.REFERENCE_TOO_LONG)
                }
                setOf(
                    ScopedExternalReference(
                        providerId = GENERIC_STATEMENT_PROVIDER_ID,
                        // No account is selected at capture time. File scope avoids false
                        // cross-account reconciliation while preserving same-batch idempotency.
                        accountScopeHash = fileHash,
                        referenceType = ROW_REFERENCE_TYPE,
                        valueHash = hashUtf8(reference),
                    ),
                )
            }
            .orEmpty()

        return StatementRowMappingResult.Mapped(
            MappedStatementRow(
                candidate = NormalizedCandidate(
                    amount = FieldCandidate(
                        value = Money(minorUnits, mapping.currency),
                        confidence = EXPLICIT_MAPPING_CONFIDENCE,
                        evidenceLocator = EvidenceLocator.TableCell(
                            row.tableRowIndex,
                            mapping.amountColumnIndex,
                        ),
                    ),
                    moneyDirection = FieldCandidate(
                        value = when (direction) {
                            StatementDirection.INBOUND -> ObservedMoneyDirection.INBOUND
                            StatementDirection.OUTBOUND -> ObservedMoneyDirection.OUTBOUND
                        },
                        confidence = EXPLICIT_MAPPING_CONFIDENCE,
                        evidenceLocator = EvidenceLocator.TableCell(
                            row.tableRowIndex,
                            when (val directionMapping = mapping.directionMapping) {
                                is StatementDirectionMapping.DirectionColumn ->
                                    directionMapping.columnIndex

                                is StatementDirectionMapping.SignedAmount ->
                                    mapping.amountColumnIndex
                            },
                        ),
                    ),
                    occurredAt = FieldCandidate(
                        value = observedTime,
                        confidence = EXPLICIT_MAPPING_CONFIDENCE,
                        evidenceLocator = EvidenceLocator.TableCell(
                            row.tableRowIndex,
                            mapping.dateColumnIndex,
                        ),
                    ),
                    counterparty = FieldCandidate(
                        value = counterparty,
                        confidence = EXPLICIT_MAPPING_CONFIDENCE,
                        evidenceLocator = EvidenceLocator.TableCell(
                            row.tableRowIndex,
                            mapping.counterpartyColumnIndex,
                        ),
                    ),
                    externalReferences = externalReferences,
                ),
            ),
        )
    }

    private fun parseObservedTime(
        value: String,
        format: StatementDateFormat,
    ): ObservedTime? {
        val normalized = value.trim()
        if (normalized.isEmpty()) return null
        val formatter = DateTimeFormatter
            .ofPattern(format.pattern, Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
        return try {
            if (!format.hasTime) {
                ObservedTime.DateOnly(LocalDate.parse(normalized, formatter))
            } else {
                val localDateTime = LocalDateTime.parse(normalized, formatter)
                ObservedTime.DateTime(
                    localDateTime = localDateTime,
                    precision = if (format.hasSeconds) {
                        DateTimePrecision.SECOND
                    } else {
                        DateTimePrecision.MINUTE
                    },
                    offset = null,
                    zoneId = null,
                    resolvedInstant = null,
                )
            }
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseAmount(
        value: String,
        format: StatementAmountFormat,
    ): BigDecimal? {
        val (pattern, normalized) = when (format) {
            StatementAmountFormat.DOT_DECIMAL ->
                PLAIN_DOT_PATTERN to value

            StatementAmountFormat.COMMA_GROUP_DOT_DECIMAL ->
                GROUPED_COMMA_DOT_PATTERN to value.replace(",", "")

            StatementAmountFormat.COMMA_DECIMAL ->
                PLAIN_COMMA_PATTERN to value.replace(',', '.')

            StatementAmountFormat.DOT_GROUP_COMMA_DECIMAL ->
                GROUPED_DOT_COMMA_PATTERN to value.replace(".", "").replace(',', '.')
        }
        if (!pattern.matches(value)) return null
        return try {
            BigDecimal(normalized)
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun StatementDirection.opposite(): StatementDirection = when (this) {
        StatementDirection.INBOUND -> StatementDirection.OUTBOUND
        StatementDirection.OUTBOUND -> StatementDirection.INBOUND
    }

    private fun hashUtf8(value: String): EvidenceHash {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return try {
            EvidenceHash.fromBytes(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun invalid(error: StatementMappingError) =
        StatementRowMappingResult.Invalid(error)

    private val PLAIN_DOT_PATTERN = Regex("[+-]?\\d+(?:\\.\\d{1,2})?")
    private val GROUPED_COMMA_DOT_PATTERN =
        Regex("[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?")
    private val PLAIN_COMMA_PATTERN = Regex("[+-]?\\d+(?:,\\d{1,2})?")
    private val GROUPED_DOT_COMMA_PATTERN =
        Regex("[+-]?(?:\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:,\\d{1,2})?")

    private const val MINOR_UNIT_SCALE = 2
    private const val EXPLICIT_MAPPING_CONFIDENCE = 1.0
    private const val ROW_REFERENCE_TYPE = "statement-row-reference"
    private val GENERIC_STATEMENT_PROVIDER_ID =
        ProviderId(GenericDelimitedStatementIdentity.PROVIDER_ID)
}
