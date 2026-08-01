package dev.bill.core.domain

import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.Money
import java.time.Instant

const val MAX_BALANCE_SNAPSHOT_NOTE_LENGTH = 200

@JvmInline
value class BalanceSnapshotId(val value: String) {
    init {
        require(value.isNotBlank()) { "Balance snapshot id cannot be blank" }
    }
}

enum class BalanceSnapshotSourceMode {
    MANUAL,
}

/** Immutable evidence of the balance a user observed at [asOf]. */
data class BalanceSnapshot(
    val id: BalanceSnapshotId,
    val accountId: AccountId,
    val observedBalance: Money,
    val asOf: Instant,
    val recordedAt: Instant,
    val note: String?,
    val sourceMode: BalanceSnapshotSourceMode,
    val creationCommandId: CommandId,
) {
    init {
        require(observedBalance.minorUnits >= 0L) {
            "Observed balance must use a non-negative user-view amount"
        }
        require(asOf <= recordedAt) { "Balance snapshot cannot be observed in the future" }
        require(note == null || note.isNotBlank()) { "Balance snapshot note cannot be blank" }
        require(note == null || note.length <= MAX_BALANCE_SNAPSHOT_NOTE_LENGTH) {
            "Balance snapshot note is too long"
        }
        require(note == null || note.none(Char::isISOControl)) {
            "Balance snapshot note cannot contain control characters"
        }
    }
}

/** Latest snapshot plus the ledger balance derived at exactly the same point in time. */
data class BalanceSnapshotComparison(
    val snapshot: BalanceSnapshot,
    val ledgerBalance: Money,
    val difference: Money,
) {
    init {
        require(snapshot.observedBalance.currency == ledgerBalance.currency) {
            "Snapshot and ledger balance currencies must match"
        }
        require(snapshot.observedBalance.currency == difference.currency) {
            "Snapshot difference currency must match"
        }
        require(
            difference.minorUnits == Math.subtractExact(
                snapshot.observedBalance.minorUnits,
                ledgerBalance.minorUnits,
            ),
        ) { "Snapshot difference must equal observed balance minus ledger balance" }
    }

    val isReconciled: Boolean
        get() = difference.minorUnits == 0L
}

fun AccountType.supportsBalanceSnapshots(): Boolean = when (this) {
    AccountType.ASSET_CASH,
    AccountType.ASSET_BANK,
    AccountType.ASSET_EWALLET_BALANCE,
    AccountType.LIABILITY_CC,
    -> true

    AccountType.ASSET_WRAPPER,
    AccountType.LIABILITY_LOAN,
    AccountType.INVESTMENT_CASH,
    AccountType.INVESTMENT_SECURITY,
    AccountType.EXPENSE_CATEGORY,
    AccountType.INCOME_CATEGORY,
    AccountType.EQUITY_ADJUSTMENT,
    -> false
}

/** Converts the signed internal ledger convention into the value shown to the user. */
fun AccountType.toUserViewBalance(signedLedgerBalance: Money): Money = when (this) {
    AccountType.LIABILITY_CC -> signedLedgerBalance.copy(
        minorUnits = Math.negateExact(signedLedgerBalance.minorUnits),
    )

    else -> signedLedgerBalance
}
