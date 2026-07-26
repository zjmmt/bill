package dev.bill.core.ledger

import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.EntryRole
import dev.bill.core.model.LedgerEntry
import dev.bill.core.model.LedgerPostingCandidate
import dev.bill.core.model.TransactionId
import dev.bill.core.model.TransactionType
import java.math.BigInteger
import java.time.Instant

sealed interface LedgerValidation {
    data class Valid(val transaction: ValidatedLedgerTransaction) : LedgerValidation

    data class TooFewEntries(val count: Int) : LedgerValidation

    data class ZeroAmountEntries(val indices: List<Int>) : LedgerValidation

    data class MixedCurrencies(val currencies: Set<CurrencyCode>) : LedgerValidation

    data class Unbalanced(
        val differences: Map<CurrencyCode, BigInteger>,
    ) : LedgerValidation

    data class InvalidEntryRoles(
        val type: TransactionType,
        val actualRoles: Set<EntryRole>,
    ) : LedgerValidation
}

class ValidatedLedgerTransaction private constructor(
    val id: TransactionId,
    val type: TransactionType,
    val occurredAt: Instant,
    entries: List<LedgerEntry>,
) {
    val entries: List<LedgerEntry> = entries.toList()

    companion object {
        internal fun from(candidate: LedgerPostingCandidate): ValidatedLedgerTransaction =
            ValidatedLedgerTransaction(
                id = candidate.id,
                type = candidate.type,
                occurredAt = candidate.occurredAt,
                entries = candidate.entries,
            )
    }
}

object LedgerValidator {
    private val counterpartRoles = setOf(
        EntryRole.FUNDING,
        EntryRole.ASSET,
        EntryRole.LIABILITY,
        EntryRole.INVESTMENT,
        EntryRole.EQUITY,
    )

    fun validate(candidate: LedgerPostingCandidate): LedgerValidation {
        val zeroAmountIndices = candidate.entries.mapIndexedNotNull { index, entry ->
            index.takeIf { entry.amount.minorUnits == 0L }
        }
        if (zeroAmountIndices.isNotEmpty()) {
            return LedgerValidation.ZeroAmountEntries(zeroAmountIndices)
        }

        if (candidate.entries.size < 2) {
            return LedgerValidation.TooFewEntries(candidate.entries.size)
        }

        val currencies = candidate.entries.mapTo(linkedSetOf()) { it.amount.currency }
        if (currencies.size != 1) {
            return LedgerValidation.MixedCurrencies(currencies)
        }

        val imbalancesByCurrency = candidate.entries
            .groupBy { it.amount.currency }
            .mapValues { (_, entries) ->
                entries.fold(BigInteger.ZERO) { sum, entry ->
                    sum + BigInteger.valueOf(entry.amount.minorUnits)
                }
            }
            .filterValues { it != BigInteger.ZERO }

        if (imbalancesByCurrency.isNotEmpty()) {
            return LedgerValidation.Unbalanced(imbalancesByCurrency)
        }

        val roles = candidate.entries.mapTo(mutableSetOf()) { it.role }
        if (!rolesAreValid(candidate, roles)) {
            return LedgerValidation.InvalidEntryRoles(candidate.type, roles)
        }

        return LedgerValidation.Valid(ValidatedLedgerTransaction.from(candidate))
    }

    private fun rolesAreValid(
        candidate: LedgerPostingCandidate,
        roles: Set<EntryRole>,
    ): Boolean {
        val hasCounterpart = roles.any(counterpartRoles::contains)
        val hasOnlyCounterpartRoles = roles.all(counterpartRoles::contains)

        return when (candidate.type) {
            TransactionType.EXPENSE ->
                EntryRole.EXPENSE in roles &&
                    hasCounterpart &&
                    candidate.entriesFollowDirections(
                        positiveRoles = setOf(EntryRole.EXPENSE),
                        negativeRoles = counterpartRoles,
                    )

            TransactionType.REFUND ->
                EntryRole.EXPENSE in roles &&
                    hasCounterpart &&
                    candidate.entriesFollowDirections(
                        positiveRoles = counterpartRoles,
                        negativeRoles = setOf(EntryRole.EXPENSE),
                    )

            TransactionType.INCOME ->
                EntryRole.INCOME in roles &&
                    hasCounterpart &&
                    candidate.entriesFollowDirections(
                        positiveRoles = counterpartRoles,
                        negativeRoles = setOf(EntryRole.INCOME),
                    )

            TransactionType.FEE ->
                EntryRole.FEE in roles &&
                    hasCounterpart &&
                    candidate.entriesFollowDirections(
                        positiveRoles = setOf(EntryRole.FEE),
                        negativeRoles = counterpartRoles,
                    )

            TransactionType.TRANSFER ->
                hasOnlyCounterpartRoles &&
                    candidate.entries.map { it.accountId }.distinct().size >= 2

            TransactionType.TOPUP ->
                roles == setOf(EntryRole.FUNDING, EntryRole.ASSET) &&
                    candidate.entriesFollowDirections(
                        positiveRoles = setOf(EntryRole.ASSET),
                        negativeRoles = setOf(EntryRole.FUNDING),
                    )

            TransactionType.LIABILITY_DRAW ->
                EntryRole.LIABILITY in roles &&
                    (EntryRole.ASSET in roles || EntryRole.FUNDING in roles) &&
                    hasOnlyCounterpartRoles &&
                    candidate.entriesFollowDirections(
                        positiveRoles = setOf(EntryRole.ASSET, EntryRole.FUNDING),
                        negativeRoles = setOf(EntryRole.LIABILITY),
                    )

            TransactionType.LIABILITY_REPAY ->
                roles == setOf(EntryRole.LIABILITY, EntryRole.FUNDING) &&
                    candidate.entriesFollowDirections(
                        positiveRoles = setOf(EntryRole.LIABILITY),
                        negativeRoles = setOf(EntryRole.FUNDING),
                    )

            TransactionType.INVEST_BUY ->
                roles == setOf(EntryRole.INVESTMENT, EntryRole.FUNDING) &&
                    candidate.entriesFollowDirections(
                        positiveRoles = setOf(EntryRole.INVESTMENT),
                        negativeRoles = setOf(EntryRole.FUNDING),
                    )

            TransactionType.INVEST_SELL ->
                roles == setOf(EntryRole.INVESTMENT, EntryRole.FUNDING) &&
                    candidate.entriesFollowDirections(
                        positiveRoles = setOf(EntryRole.FUNDING),
                        negativeRoles = setOf(EntryRole.INVESTMENT),
                    )

            TransactionType.ADJUSTMENT ->
                EntryRole.EQUITY in roles && roles.any { it != EntryRole.EQUITY }
        }
    }

    private fun LedgerPostingCandidate.entriesFollowDirections(
        positiveRoles: Set<EntryRole>,
        negativeRoles: Set<EntryRole>,
    ): Boolean = entries.all { entry ->
        when (entry.role) {
            in positiveRoles -> entry.amount.minorUnits > 0L
            in negativeRoles -> entry.amount.minorUnits < 0L
            else -> false
        }
    }
}
