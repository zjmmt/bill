package dev.bill.data.local

import dev.bill.core.domain.CommandId
import dev.bill.core.domain.BalanceSnapshot
import dev.bill.core.domain.BalanceSnapshotId
import dev.bill.core.domain.BalanceSnapshotSourceMode
import dev.bill.core.domain.DraftId
import dev.bill.core.domain.DraftState
import dev.bill.core.domain.LedgerAccount
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.PostedTransaction
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.domain.TransactionStatus
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.EntryRole
import dev.bill.core.model.LedgerEntry
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionId
import dev.bill.core.model.TransactionType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RepositoryFingerprintsTest {
    private val early = Instant.parse("2026-07-19T00:00:00Z")
    private val later = Instant.parse("2026-07-20T00:00:00Z")

    @Test
    fun `account retry fingerprint ignores regenerated lifecycle timestamps`() {
        val account = account(early)
        val opening = opening(account, early)

        val replayFingerprint = Fingerprints.createAccount(
            account.copy(createdAt = later),
            opening.copy(occurredAt = later, confirmedAt = later),
        )

        assertEquals(Fingerprints.createAccount(account, opening), replayFingerprint)
        assertNotEquals(
            replayFingerprint,
            Fingerprints.createAccount(account.copy(name = "DIFFERENT"), opening),
        )
    }

    @Test
    fun `draft retry fingerprint ignores regenerated times but preserves business intent`() {
        val draft = draft(early)
        val replay = draft.copy(
            occurredAt = later,
            createdAt = later,
            updatedAt = later,
        )

        assertEquals(Fingerprints.createDraft(draft), Fingerprints.createDraft(replay))
        assertNotEquals(
            Fingerprints.createDraft(draft),
            Fingerprints.createDraft(replay.copy(amount = Money.cny(2_501L))),
        )
    }

    @Test
    fun `balance snapshot retry ignores recording time but preserves observed intent`() {
        val snapshot = BalanceSnapshot(
            id = BalanceSnapshotId("balance-snapshot:stable-command"),
            accountId = AccountId("account:stable-command"),
            observedBalance = Money.cny(12_345L),
            asOf = early,
            recordedAt = early,
            note = "manual check",
            sourceMode = BalanceSnapshotSourceMode.MANUAL,
            creationCommandId = CommandId("stable-command"),
        )
        val replay = snapshot.copy(recordedAt = later)

        assertEquals(
            Fingerprints.createBalanceSnapshot(snapshot),
            Fingerprints.createBalanceSnapshot(replay),
        )
        assertNotEquals(
            Fingerprints.createBalanceSnapshot(snapshot),
            Fingerprints.createBalanceSnapshot(replay.copy(observedBalance = Money.cny(12_346L))),
        )
        assertNotEquals(
            Fingerprints.createBalanceSnapshot(snapshot),
            Fingerprints.createBalanceSnapshot(replay.copy(asOf = later)),
        )
    }

    private fun account(createdAt: Instant) = LedgerAccount(
        id = AccountId("account:stable-command"),
        name = "TEST ACCOUNT",
        normalizedName = "test account",
        type = AccountType.ASSET_BANK,
        currency = CurrencyCode.CNY,
        isSystem = false,
        isArchived = false,
        createdAt = createdAt,
        creationCommandId = CommandId("stable-command"),
    )

    private fun opening(account: LedgerAccount, occurredAt: Instant) = PostedTransaction(
        id = TransactionId("transaction:opening:stable-command"),
        draftId = null,
        type = TransactionType.ADJUSTMENT,
        status = TransactionStatus.ACTIVE,
        sourceMode = TransactionSourceMode.MANUAL,
        occurredAt = occurredAt,
        confirmedAt = occurredAt,
        title = "OPENING BALANCE",
        note = null,
        commandId = account.creationCommandId,
        entries = listOf(
            LedgerEntry(account.id, Money.cny(12_300L), EntryRole.ASSET),
            LedgerEntry(
                AccountId("system:equity:opening"),
                Money.cny(-12_300L),
                EntryRole.EQUITY,
            ),
        ),
    )

    private fun draft(occurredAt: Instant) = ManualDraft(
        id = DraftId("draft:stable-command"),
        state = DraftState.WAITING_USER,
        type = TransactionType.EXPENSE,
        amount = Money.cny(2_500L),
        occurredAt = occurredAt,
        counterparty = "TEST COUNTERPARTY",
        note = "fixture",
        fundingAccountId = null,
        createdAt = occurredAt,
        updatedAt = occurredAt,
        creationCommandId = CommandId("stable-command"),
    )
}
