package dev.bill.application

import dev.bill.core.domain.AccountBalance
import dev.bill.core.domain.AuditAction
import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.CommandId
import dev.bill.core.domain.DraftId
import dev.bill.core.domain.DraftState
import dev.bill.core.domain.LedgerAccount
import dev.bill.core.domain.LedgerRepository
import dev.bill.core.domain.LedgerState
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.PostedTransaction
import dev.bill.core.domain.RepositoryWriteResult
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.core.domain.ReviewDraft
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.EntryRole
import dev.bill.core.model.LedgerEntry
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionId
import dev.bill.core.model.TransactionType
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.EvidenceLocator
import dev.bill.source.contract.FieldCandidate
import dev.bill.source.contract.NormalizedCandidate
import dev.bill.source.contract.ObservedMoneyDirection
import dev.bill.source.contract.SourceFamily
import dev.bill.source.review.SourceProposalRecord
import dev.bill.source.review.SourceReviewRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillServiceTest {
    private val now = Instant.parse("2026-07-19T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `blank and zero opening balances create an account without a posting`() = runBlocking {
        listOf("", "   ", "0", "0.00").forEachIndexed { index, amountText ->
            val repository = FakeLedgerRepository()
            val service = BillService(repository, clock)

            val result = service.createAccount(
                accountCommand(
                    id = "zero-$index",
                    openingBalanceText = amountText,
                ),
            )

            assertEquals(
                OperationResult.Success("account:zero-$index"),
                result,
            )
            val call = requireNotNull(repository.createAccountCall)
            assertNull(call.openingTransaction)
            assertEquals(
                listOf(AuditAction.ACCOUNT_CREATED),
                call.auditRecords.map(AuditRecord::action),
            )
        }
    }

    @Test
    fun `manual amount rejects blank zero excessive precision overflow and malformed text`() = runBlocking {
        val invalidAmounts = listOf(
            "",
            "   ",
            "0",
            "0.00",
            "12.345",
            "-1",
            "+1",
            "01",
            "1,000",
            "not-a-number",
            "1000000000000000",
            "92233720368547758.08",
        )

        invalidAmounts.forEachIndexed { index, amountText ->
            val repository = FakeLedgerRepository()
            val result = BillService(repository, clock).createManualDraft(
                draftCommand(
                    id = "invalid-$index",
                    amountText = amountText,
                ),
            )

            assertEquals(
                "Unexpected result for '$amountText'",
                OperationResult.Failure(OperationError.INVALID_AMOUNT),
                result,
            )
            assertNull(repository.createDraftCall)
        }
    }

    @Test
    fun `one and two decimal places are converted to exact minor units`() = runBlocking {
        val cases = listOf("12.3" to 1_230L, "12.34" to 1_234L)

        cases.forEachIndexed { index, (amountText, expectedMinorUnits) ->
            val repository = FakeLedgerRepository()
            val result = BillService(repository, clock).createManualDraft(
                draftCommand(
                    id = "decimal-$index",
                    amountText = amountText,
                ),
            )

            assertEquals(
                OperationResult.Success("draft:decimal-$index"),
                result,
            )
            assertEquals(
                Money.cny(expectedMinorUnits),
                requireNotNull(repository.createDraftCall).draft.amount,
            )
        }
    }

    @Test
    fun `largest supported input stays exact and the next integer digit is rejected`() = runBlocking {
        val acceptedRepository = FakeLedgerRepository()
        val accepted = BillService(acceptedRepository, clock).createManualDraft(
            draftCommand(
                id = "large-accepted",
                amountText = "999999999999999.99",
            ),
        )

        assertEquals(
            OperationResult.Success("draft:large-accepted"),
            accepted,
        )
        assertEquals(
            Money.cny(99_999_999_999_999_999L),
            requireNotNull(acceptedRepository.createDraftCall).draft.amount,
        )

        val rejectedRepository = FakeLedgerRepository()
        val rejected = BillService(rejectedRepository, clock).createManualDraft(
            draftCommand(
                id = "large-rejected",
                amountText = "1000000000000000.00",
            ),
        )

        assertEquals(
            OperationResult.Failure(OperationError.INVALID_AMOUNT),
            rejected,
        )
        assertNull(rejectedRepository.createDraftCall)
    }

    @Test
    fun `account creation normalizes identity and posts a balanced opening balance`() = runBlocking {
        val repository = FakeLedgerRepository()
        val service = BillService(repository, clock)

        val result = service.createAccount(
            accountCommand(
                id = "create-bank",
                name = "  TEST   BANK  ",
                openingBalanceText = "123.45",
            ),
        )

        assertEquals(OperationResult.Success("account:create-bank"), result)
        val call = requireNotNull(repository.createAccountCall)
        assertEquals(AccountId("account:create-bank"), call.account.id)
        assertEquals("TEST BANK", call.account.name)
        assertEquals("test bank", call.account.normalizedName)
        assertEquals(now, call.account.createdAt)
        assertFalse(call.account.isSystem)

        val opening = requireNotNull(call.openingTransaction)
        assertEquals(TransactionId("transaction:opening:create-bank"), opening.id)
        assertEquals(TransactionType.ADJUSTMENT, opening.type)
        assertEquals(listOf(12_345L, -12_345L), opening.entries.map { it.amount.minorUnits })
        assertEquals(0L, opening.entries.sumOf { it.amount.minorUnits })
        assertEquals(
            listOf(AuditAction.ACCOUNT_CREATED, AuditAction.OPENING_BALANCE_POSTED),
            call.auditRecords.map(AuditRecord::action),
        )
    }

    @Test
    fun `credit card opening balance is stored negative but displayed as positive debt`() = runBlocking {
        val repository = FakeLedgerRepository()
        val service = BillService(repository, clock)

        val result = service.createAccount(
            accountCommand(
                id = "create-credit",
                type = AccountType.LIABILITY_CC,
                openingBalanceText = "67.00",
            ),
        )

        assertEquals(OperationResult.Success("account:create-credit"), result)
        val opening = requireNotNull(repository.createAccountCall?.openingTransaction)
        assertEquals(-6_700L, opening.entries.first().amount.minorUnits)
        assertEquals(EntryRole.LIABILITY, opening.entries.first().role)
        assertEquals(6_700L, opening.entries.last().amount.minorUnits)

        val snapshot = service.observeSnapshot().first()
        val totals = snapshot.overview.currencyBalances.single()
        assertEquals(Money.cny(0L), totals.assets)
        assertEquals(Money.cny(6_700L), totals.liabilities)
        assertEquals(Money.cny(-6_700L), totals.netWorth)
        assertEquals(Money.cny(6_700L), snapshot.accounts.single().displayBalance)
        assertTrue(snapshot.accounts.single().isLiability)
    }

    @Test
    fun `USD can create a bank account but not an e-wallet`() = runBlocking {
        val bankRepository = FakeLedgerRepository()
        val bankResult = BillService(bankRepository, clock).createAccount(
            accountCommand(
                id = "usd-bank",
                type = AccountType.ASSET_BANK,
                openingBalanceText = "123.45",
                currency = CurrencyCode.USD,
            ),
        )

        assertEquals(OperationResult.Success("account:usd-bank"), bankResult)
        val bankCall = requireNotNull(bankRepository.createAccountCall)
        assertEquals(CurrencyCode.USD, bankCall.account.currency)
        assertTrue(requireNotNull(bankCall.openingTransaction).entries.all {
            it.amount.currency == CurrencyCode.USD
        })

        val walletRepository = FakeLedgerRepository()
        val walletResult = BillService(walletRepository, clock).createAccount(
            accountCommand(
                id = "usd-wallet",
                type = AccountType.ASSET_EWALLET_BALANCE,
                currency = CurrencyCode.USD,
            ),
        )

        assertEquals(OperationResult.Failure(OperationError.UNSUPPORTED_CURRENCY), walletResult)
        assertNull(walletRepository.createAccountCall)
    }

    @Test
    fun `invalid account metadata is rejected before repository access`() = runBlocking {
        val invalidNames = listOf("", "   ", "bad\u0000name", "x".repeat(41))
        invalidNames.forEachIndexed { index, name ->
            val repository = FakeLedgerRepository()
            val result = BillService(repository, clock).createAccount(
                accountCommand(id = "bad-name-$index", name = name),
            )
            assertEquals(OperationResult.Failure(OperationError.INVALID_NAME), result)
            assertNull(repository.createAccountCall)
        }

        val repository = FakeLedgerRepository()
        val result = BillService(repository, clock).createAccount(
            accountCommand(
                id = "unsupported-account",
                type = AccountType.ASSET_WRAPPER,
            ),
        )
        assertEquals(OperationResult.Failure(OperationError.UNSUPPORTED_ACCOUNT_TYPE), result)
        assertNull(repository.createAccountCall)
    }

    @Test
    fun `repository duplicate idempotent and command collision statuses map safely`() = runBlocking {
        val duplicateRepository = FakeLedgerRepository().apply {
            createAccountResult = RepositoryWriteResult(RepositoryWriteStatus.DUPLICATE_NAME)
        }
        assertEquals(
            OperationResult.Failure(OperationError.DUPLICATE_ACCOUNT_NAME),
            BillService(duplicateRepository, clock).createAccount(accountCommand(id = "duplicate")),
        )

        val collisionRepository = FakeLedgerRepository().apply {
            createAccountResult = RepositoryWriteResult(RepositoryWriteStatus.COMMAND_COLLISION)
        }
        assertEquals(
            OperationResult.Failure(OperationError.CONFLICT),
            BillService(collisionRepository, clock).createAccount(accountCommand(id = "collision")),
        )

        val idempotentRepository = FakeLedgerRepository().apply {
            createAccountResult = RepositoryWriteResult(
                RepositoryWriteStatus.ALREADY_APPLIED,
                "account:idempotent",
            )
        }
        assertEquals(
            OperationResult.Success("account:idempotent"),
            BillService(idempotentRepository, clock).createAccount(accountCommand(id = "idempotent")),
        )
    }

    @Test
    fun `manual draft creation sanitizes fields and clamps future occurrence time`() = runBlocking {
        val repository = FakeLedgerRepository()
        val result = BillService(repository, clock).createManualDraft(
            draftCommand(
                id = "create-draft",
                amountText = "8.20",
                counterparty = "  TEST   COUNTERPARTY  ",
                note = "  local fixture note  ",
                occurredAt = now.plusSeconds(86_400),
            ),
        )

        assertEquals(OperationResult.Success("draft:create-draft"), result)
        val call = requireNotNull(repository.createDraftCall)
        assertEquals(DraftId("draft:create-draft"), call.draft.id)
        assertEquals(DraftState.WAITING_USER, call.draft.state)
        assertEquals(Money.cny(820L), call.draft.amount)
        assertEquals("TEST COUNTERPARTY", call.draft.counterparty)
        assertEquals("local fixture note", call.draft.note)
        assertEquals(now, call.draft.occurredAt)
        assertNull(call.draft.fundingAccountId)
        assertEquals(AuditAction.MANUAL_DRAFT_CREATED, call.auditRecord.action)
    }

    @Test
    fun `USD manual draft can only fund through a USD bank or credit card`() = runBlocking {
        val cnyBank = account("cny-bank", AccountType.ASSET_BANK, currency = CurrencyCode.CNY)
        val usdBank = account("usd-bank", AccountType.ASSET_BANK, currency = CurrencyCode.USD)
        val repository = FakeLedgerRepository(
            ledgerState(
                balances = listOf(
                    AccountBalance(cnyBank, Money.cny(0L)),
                    AccountBalance(usdBank, Money(0L, CurrencyCode.USD)),
                ),
            ),
        )
        val service = BillService(repository, clock)
        val command = draftCommand(
            id = "usd-manual",
            amountText = "8.20",
            currency = CurrencyCode.USD,
        )

        assertEquals(OperationResult.Success("draft:usd-manual"), service.createManualDraft(command))
        val draftId = DraftId("draft:usd-manual")
        assertEquals(
            OperationResult.Failure(OperationError.UNSUPPORTED_ACCOUNT_TYPE),
            service.selectFundingAccount(CommandId("select-cny"), draftId, cnyBank.id),
        )
        assertEquals(
            OperationResult.Success(draftId.value),
            service.selectFundingAccount(CommandId("select-usd"), draftId, usdBank.id),
        )

        assertEquals(
            OperationResult.Success("transaction:confirm:confirm-usd"),
            service.confirmDraft(CommandId("confirm-usd"), draftId),
        )
        val transaction = requireNotNull(repository.confirmDraftCall).transaction
        assertTrue(transaction.entries.all { it.amount.currency == CurrencyCode.USD })
    }

    @Test
    fun `external source completion creates an evidenced review draft instead of manual entry`() =
        runBlocking {
            val ledgerRepository = FakeLedgerRepository()
            val sourceRepository = FakeSourceReviewRepository(
                initialProposals = listOf(sourceProposal("source-proposal")),
            )
            val service = BillService(ledgerRepository, clock, sourceRepository)

            val result = service.createExternalDraft(
                CreateExternalDraftCommand(
                    commandId = CommandId("external-draft"),
                    proposalId = "source-proposal",
                    type = TransactionType.EXPENSE,
                    amountText = "8.20",
                    counterparty = "  TEST   COUNTERPARTY  ",
                    note = "  local fixture note  ",
                    occurredAt = now.plusSeconds(60),
                ),
            )

            assertEquals(OperationResult.Success("draft:external-draft"), result)
            val call = requireNotNull(sourceRepository.completeCall)
            assertEquals("source-proposal", call.proposalId)
            assertEquals(TransactionSourceMode.EXTERNAL, call.draft.sourceMode)
            assertEquals(Money.cny(820L), call.draft.amount)
            assertEquals("TEST COUNTERPARTY", call.draft.counterparty)
            assertEquals(now, call.draft.occurredAt)
            assertEquals(AuditAction.EXTERNAL_DRAFT_CREATED, call.auditRecord.action)
            assertNull(ledgerRepository.createDraftCall)
        }

    @Test
    fun `wallet source rejects USD before attempting external draft completion`() = runBlocking {
        val sourceRepository = FakeSourceReviewRepository(
            initialProposals = listOf(
                sourceProposal(
                    id = "alipay-proposal",
                    sourceFamily = SourceFamily.ALIPAY,
                ),
            ),
        )
        val service = BillService(FakeLedgerRepository(), clock, sourceRepository)

        val result = service.createExternalDraft(
            CreateExternalDraftCommand(
                commandId = CommandId("alipay-usd"),
                proposalId = "alipay-proposal",
                type = TransactionType.EXPENSE,
                amountText = "8.20",
                counterparty = "TEST COUNTERPARTY",
                note = null,
                currency = CurrencyCode.USD,
            ),
        )

        assertEquals(
            OperationResult.Failure(OperationError.SOURCE_CURRENCY_NOT_ALLOWED),
            result,
        )
        assertNull(sourceRepository.completeCall)
    }

    @Test
    fun `source review can be dismissed with a safe audit record`() = runBlocking {
        val sourceRepository = FakeSourceReviewRepository()
        val service = BillService(FakeLedgerRepository(), clock, sourceRepository)

        val result = service.dismissSourceProposal(
            commandId = CommandId("dismiss-source"),
            proposalId = "source-proposal",
        )

        assertEquals(OperationResult.Success("source-proposal"), result)
        val call = requireNotNull(sourceRepository.dismissCall)
        assertEquals("source-proposal", call.proposalId)
        assertEquals(AuditAction.SOURCE_PROPOSAL_DISMISSED, call.auditRecord.action)
        assertEquals("source_proposal", call.auditRecord.entityType)
        assertEquals(now, call.auditRecord.occurredAt)
    }

    @Test
    fun `invalid manual draft fields are rejected before repository access`() = runBlocking {
        val invalidCommands = listOf(
            draftCommand(id = "invalid-type", type = TransactionType.TRANSFER),
            draftCommand(id = "blank-counterparty", counterparty = "   "),
            draftCommand(id = "control-counterparty", counterparty = "bad\nvalue"),
            draftCommand(id = "long-counterparty", counterparty = "x".repeat(81)),
            draftCommand(id = "control-note", note = "bad\u0000note"),
            draftCommand(id = "long-note", note = "x".repeat(201)),
        )
        val expectedErrors = listOf(
            OperationError.INVALID_STATE,
            OperationError.INVALID_COUNTERPARTY,
            OperationError.INVALID_COUNTERPARTY,
            OperationError.INVALID_COUNTERPARTY,
            OperationError.NOTE_TOO_LONG,
            OperationError.NOTE_TOO_LONG,
        )

        invalidCommands.zip(expectedErrors).forEach { (command, expectedError) ->
            val repository = FakeLedgerRepository()
            val result = BillService(repository, clock).createManualDraft(command)
            assertEquals(OperationResult.Failure(expectedError), result)
            assertNull(repository.createDraftCall)
        }
    }

    @Test
    fun `income cannot select or confirm a credit card funding account`() = runBlocking {
        val credit = account(
            id = "credit",
            type = AccountType.LIABILITY_CC,
        )
        val incomeDraftWithoutFunding = draft(
            id = "income-select",
            type = TransactionType.INCOME,
        )
        val selectRepository = FakeLedgerRepository(
            state = ledgerState(
                balances = listOf(AccountBalance(credit, Money.cny(0L))),
                drafts = listOf(incomeDraftWithoutFunding),
            ),
        )

        val selectResult = BillService(selectRepository, clock).selectFundingAccount(
            commandId = CommandId("select-credit"),
            draftId = incomeDraftWithoutFunding.id,
            accountId = credit.id,
        )

        assertEquals(
            OperationResult.Failure(OperationError.UNSUPPORTED_ACCOUNT_TYPE),
            selectResult,
        )
        assertNull(selectRepository.selectFundingCall)

        val incomeDraftWithFunding = incomeDraftWithoutFunding.copy(
            id = DraftId("income-confirm"),
            fundingAccountId = credit.id,
        )
        val confirmRepository = FakeLedgerRepository(
            state = ledgerState(
                balances = listOf(AccountBalance(credit, Money.cny(0L))),
                drafts = listOf(incomeDraftWithFunding),
            ),
        )
        val confirmResult = BillService(confirmRepository, clock).confirmDraft(
            commandId = CommandId("confirm-credit"),
            draftId = incomeDraftWithFunding.id,
        )

        assertEquals(
            OperationResult.Failure(OperationError.UNSUPPORTED_ACCOUNT_TYPE),
            confirmResult,
        )
        assertNull(confirmRepository.confirmDraftCall)
    }

    @Test
    fun `funding selection and confirmation produce a balanced expense transaction`() = runBlocking {
        val bank = account(id = "bank", type = AccountType.ASSET_BANK)
        val pendingDraft = draft(id = "expense", type = TransactionType.EXPENSE)
        val repository = FakeLedgerRepository(
            state = ledgerState(
                balances = listOf(AccountBalance(bank, Money.cny(10_000L))),
                drafts = listOf(pendingDraft),
            ),
        )
        val service = BillService(repository, clock)

        assertEquals(1, service.observeSnapshot().first().overview.hardBlockCount)

        val selectResult = service.selectFundingAccount(
            commandId = CommandId("select-bank"),
            draftId = pendingDraft.id,
            accountId = bank.id,
        )
        assertEquals(OperationResult.Success(pendingDraft.id.value), selectResult)
        val selectCall = requireNotNull(repository.selectFundingCall)
        assertEquals(bank.id, selectCall.accountId)
        assertEquals(AuditAction.FUNDING_ACCOUNT_SELECTED, selectCall.auditRecord.action)
        assertEquals(0, service.observeSnapshot().first().overview.hardBlockCount)

        val confirmResult = service.confirmDraft(
            commandId = CommandId("confirm-expense"),
            draftId = pendingDraft.id,
        )
        assertEquals(
            OperationResult.Success("transaction:confirm:confirm-expense"),
            confirmResult,
        )

        val confirmCall = requireNotNull(repository.confirmDraftCall)
        assertEquals(pendingDraft.id, confirmCall.transaction.draftId)
        assertEquals(TransactionType.EXPENSE, confirmCall.transaction.type)
        assertEquals(TransactionId("transaction:confirm:confirm-expense"), confirmCall.transaction.id)
        assertEquals(listOf(2_500L, -2_500L), confirmCall.transaction.entries.map { it.amount.minorUnits })
        assertEquals(0L, confirmCall.transaction.entries.sumOf { it.amount.minorUnits })
        assertEquals(AuditAction.DRAFT_CONFIRMED, confirmCall.auditRecord.action)
        assertTrue(repository.state.value.pendingDrafts.isEmpty())
        assertEquals(7_500L, repository.state.value.accountBalances.single().balance.minorUnits)
    }

    @Test
    fun `confirmation preserves an external draft source mode`() = runBlocking {
        val bank = account(id = "external-bank", type = AccountType.ASSET_BANK)
        val externalDraft = draft(
            id = "external-expense",
            fundingAccountId = bank.id,
            sourceMode = TransactionSourceMode.EXTERNAL,
        )
        val repository = FakeLedgerRepository(
            ledgerState(
                balances = listOf(AccountBalance(bank, Money.cny(10_000L))),
                drafts = listOf(externalDraft),
            ),
        )

        val result = BillService(repository, clock).confirmDraft(
            commandId = CommandId("confirm-external"),
            draftId = externalDraft.id,
        )

        assertTrue(result is OperationResult.Success)
        assertEquals(
            TransactionSourceMode.EXTERNAL,
            repository.confirmDraftCall?.transaction?.sourceMode,
        )
    }

    @Test
    fun `confirmation requires an existing selected account and a reviewable draft`() = runBlocking {
        val noAccountDraft = draft(id = "no-account")
        val noAccountRepository = FakeLedgerRepository(
            ledgerState(drafts = listOf(noAccountDraft)),
        )
        assertEquals(
            OperationResult.Failure(OperationError.ACCOUNT_REQUIRED),
            BillService(noAccountRepository, clock).confirmDraft(
                CommandId("confirm-no-account"),
                noAccountDraft.id,
            ),
        )

        val missingFundingDraft = noAccountDraft.copy(
            id = DraftId("missing-funding"),
            fundingAccountId = AccountId("missing-account"),
        )
        val missingAccountRepository = FakeLedgerRepository(
            ledgerState(drafts = listOf(missingFundingDraft)),
        )
        assertEquals(
            OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND),
            BillService(missingAccountRepository, clock).confirmDraft(
                CommandId("confirm-missing-account"),
                missingFundingDraft.id,
            ),
        )

    }

    @Test
    fun `confirmation retry returns success when the exact command transaction already exists`() = runBlocking {
        val commandId = CommandId("idempotent-confirm")
        val draftId = DraftId("already-confirmed")
        val confirmedDraft = draft(id = draftId.value, state = DraftState.CONFIRMED)
        val existingTransaction = transaction(
            id = "transaction:confirm:${commandId.value}",
            type = TransactionType.EXPENSE,
            status = dev.bill.core.domain.TransactionStatus.ACTIVE,
            amountMinor = 2_500L,
            confirmedAt = now,
            draftId = draftId,
        ).copy(commandId = commandId)
        val repository = FakeLedgerRepository(
            ledgerState(
                drafts = listOf(confirmedDraft),
                transactions = listOf(existingTransaction),
            ),
        )

        val result = BillService(repository, clock).confirmDraft(commandId, draftId)

        assertEquals(OperationResult.Success(existingTransaction.id.value), result)
        assertNull(repository.confirmDraftCall)
    }

    @Test
    fun `confirmed draft without the requested command transaction remains invalid state`() = runBlocking {
        val confirmedDraft = draft(id = "already-confirmed", state = DraftState.CONFIRMED)
        val unrelatedTransaction = transaction(
            id = "transaction:confirm:another-command",
            type = TransactionType.EXPENSE,
            status = dev.bill.core.domain.TransactionStatus.ACTIVE,
            amountMinor = 2_500L,
            confirmedAt = now,
            draftId = confirmedDraft.id,
        )
        val repository = FakeLedgerRepository(
            ledgerState(
                drafts = listOf(confirmedDraft),
                transactions = listOf(unrelatedTransaction),
            ),
        )

        val result = BillService(repository, clock).confirmDraft(
            commandId = CommandId("missing-command"),
            draftId = confirmedDraft.id,
        )

        assertEquals(OperationResult.Failure(OperationError.INVALID_STATE), result)
        assertNull(repository.confirmDraftCall)
    }

    @Test
    fun `snapshot reports three unconfigured sources with fallback required`() = runBlocking {
        val snapshot = BillService(FakeLedgerRepository(), clock).observeSnapshot().first()

        assertEquals(SourceKind.entries.toSet(), snapshot.overview.sourceHealth.map { it.kind }.toSet())
        assertEquals(3, snapshot.overview.sourceHealth.size)
        assertTrue(
            snapshot.overview.sourceHealth.all {
                it.state == SourceHealthState.FALLBACK_REQUIRED
            },
        )
        assertEquals(
            listOf("alipay", "wechat", "bank"),
            snapshot.overview.sourceHealth.map { it.id },
        )
    }

    @Test
    fun `snapshot carries source duplicate evidence into the review count`() = runBlocking {
        val sourceRepository = FakeSourceReviewRepository(
            initialProposals = listOf(
                SourceProposalRecord(
                    id = "proposal-duplicate",
                    rawEventId = "raw-duplicate",
                    parseAttemptId = "attempt-duplicate",
                    sourceFamily = SourceFamily.GENERIC,
                    captureMethod = CaptureMethod.SHARE_TEXT,
                    capturedAt = now,
                    diagnostic = null,
                    candidate = null,
                    isPossibleDuplicate = true,
                ),
            ),
        )

        val snapshot = BillService(
            repository = FakeLedgerRepository(),
            clock = clock,
            sourceReviewRepository = sourceRepository,
        ).observeSnapshot().first()

        assertEquals(1, snapshot.pendingSourceReviews.size)
        assertTrue(snapshot.pendingSourceReviews.single().isPossibleDuplicate)
        assertEquals(1, snapshot.overview.possibleDuplicateCount)
    }

    @Test
    fun `snapshot presents generic selected text files as their own review kind`() = runBlocking {
        val sourceRepository = FakeSourceReviewRepository(
            initialProposals = listOf(
                SourceProposalRecord(
                    id = "proposal-file",
                    rawEventId = "raw-file",
                    parseAttemptId = "attempt-file",
                    sourceFamily = SourceFamily.GENERIC,
                    captureMethod = CaptureMethod.STATEMENT_IMPORT,
                    capturedAt = now,
                    diagnostic = null,
                    candidate = null,
                    isPossibleDuplicate = false,
                ),
            ),
        )

        val snapshot = BillService(
            repository = FakeLedgerRepository(),
            clock = clock,
            sourceReviewRepository = sourceRepository,
        ).observeSnapshot().first()

        assertEquals(
            SourceReviewKind.SELECTED_TEXT_FILE,
            snapshot.pendingSourceReviews.single().kind,
        )
    }

    @Test
    fun `snapshot presents photo OCR suggestions as editable income defaults`() = runBlocking {
        val sourceRepository = FakeSourceReviewRepository(
            initialProposals = listOf(
                SourceProposalRecord(
                    id = "proposal-photo-ocr",
                    rawEventId = "raw-photo-ocr",
                    parseAttemptId = "attempt-photo-ocr",
                    sourceFamily = SourceFamily.GENERIC,
                    captureMethod = CaptureMethod.PHOTO_OCR,
                    capturedAt = now,
                    diagnostic = null,
                    candidate = NormalizedCandidate(
                        amount = FieldCandidate(
                            value = Money.cny(6_600L),
                            confidence = 0.86,
                            evidenceLocator = EvidenceLocator.WholePayload,
                        ),
                        moneyDirection = FieldCandidate(
                            value = ObservedMoneyDirection.INBOUND,
                            confidence = 0.82,
                            evidenceLocator = EvidenceLocator.WholePayload,
                        ),
                        counterparty = FieldCandidate(
                            value = "测试付款方",
                            confidence = 0.78,
                            evidenceLocator = EvidenceLocator.WholePayload,
                        ),
                    ),
                    isPossibleDuplicate = false,
                ),
            ),
        )

        val snapshot = BillService(
            repository = FakeLedgerRepository(),
            clock = clock,
            sourceReviewRepository = sourceRepository,
        ).observeSnapshot().first()
        val review = snapshot.pendingSourceReviews.single()

        assertEquals(SourceReviewKind.PHOTO_OCR, review.kind)
        assertEquals(DraftSummaryKind.INCOME, review.suggestedKind)
        assertEquals(6_600L, review.suggestedAmount?.minorUnits)
        assertEquals("测试付款方", review.suggestedCounterparty)
    }

    @Test
    fun `snapshot calculates CNY net worth and excludes system and archived accounts`() = runBlocking {
        val bank = account(id = "bank", type = AccountType.ASSET_BANK, createdAt = now.minusSeconds(30))
        val cash = account(id = "cash", type = AccountType.ASSET_CASH, createdAt = now.minusSeconds(20))
        val credit = account(id = "credit", type = AccountType.LIABILITY_CC, createdAt = now.minusSeconds(10))
        val system = account(
            id = "system-fixture",
            type = AccountType.EXPENSE_CATEGORY,
            isSystem = true,
        )
        val archived = account(
            id = "archived-fixture",
            type = AccountType.ASSET_BANK,
            isArchived = true,
        )
        val repository = FakeLedgerRepository(
            ledgerState(
                balances = listOf(
                    AccountBalance(bank, Money.cny(100_000L)),
                    AccountBalance(cash, Money.cny(25_000L)),
                    AccountBalance(credit, Money.cny(-40_000L)),
                    AccountBalance(system, Money.cny(999_999L)),
                    AccountBalance(archived, Money.cny(888_888L)),
                ),
            ),
        )

        val snapshot = BillService(repository, clock).observeSnapshot().first()

        val totals = snapshot.overview.currencyBalances.single()
        assertEquals(Money.cny(125_000L), totals.assets)
        assertEquals(Money.cny(40_000L), totals.liabilities)
        assertEquals(Money.cny(85_000L), totals.netWorth)
        assertEquals(listOf("bank", "cash", "credit"), snapshot.accounts.map(AccountSummary::id))
        assertEquals(Money.cny(40_000L), snapshot.accounts.last().displayBalance)
        assertTrue(snapshot.accounts.last().isLiability)
    }

    @Test
    fun `snapshot keeps USD totals separate from CNY without an implied exchange rate`() = runBlocking {
        val cnyBank = account(
            id = "cny-bank",
            type = AccountType.ASSET_BANK,
            currency = CurrencyCode.CNY,
        )
        val usdBank = account(
            id = "usd-bank",
            type = AccountType.ASSET_BANK,
            currency = CurrencyCode.USD,
        )
        val usdCard = account(
            id = "usd-card",
            type = AccountType.LIABILITY_CC,
            currency = CurrencyCode.USD,
        )
        val snapshot = BillService(
            FakeLedgerRepository(
                ledgerState(
                    balances = listOf(
                        AccountBalance(cnyBank, Money.cny(10_000L)),
                        AccountBalance(usdBank, Money(20_000L, CurrencyCode.USD)),
                        AccountBalance(usdCard, Money(-5_000L, CurrencyCode.USD)),
                    ),
                ),
            ),
            clock,
        ).observeSnapshot().first()

        val byCurrency = snapshot.overview.currencyBalances.associateBy { it.currency }
        assertEquals(Money.cny(10_000L), byCurrency.getValue(CurrencyCode.CNY).netWorth)
        assertEquals(Money(20_000L, CurrencyCode.USD), byCurrency.getValue(CurrencyCode.USD).assets)
        assertEquals(Money(5_000L, CurrencyCode.USD), byCurrency.getValue(CurrencyCode.USD).liabilities)
        assertEquals(Money(15_000L, CurrencyCode.USD), byCurrency.getValue(CurrencyCode.USD).netWorth)
    }

    @Test
    fun `snapshot keeps only pending drafts and active recent transactions`() = runBlocking {
        val waiting = draft(id = "waiting", state = DraftState.WAITING_USER, updatedAt = now.minusSeconds(20))
        val edited = draft(id = "edited", state = DraftState.EDITED, updatedAt = now.minusSeconds(10))
        val dismissed = draft(id = "dismissed", state = DraftState.DISMISSED)
        val activeExpense = transaction(
            id = "active-expense",
            type = TransactionType.EXPENSE,
            status = dev.bill.core.domain.TransactionStatus.ACTIVE,
            amountMinor = 1_230L,
            confirmedAt = now,
            draftId = DraftId("source-expense"),
        )
        val voidedIncome = transaction(
            id = "voided-income",
            type = TransactionType.INCOME,
            status = dev.bill.core.domain.TransactionStatus.VOIDED,
            amountMinor = 2_340L,
            confirmedAt = now.plusSeconds(1),
            draftId = DraftId("source-income"),
        )
        val repository = FakeLedgerRepository(
            ledgerState(
                drafts = listOf(waiting, edited, dismissed),
                transactions = listOf(activeExpense, voidedIncome),
            ),
        )

        val snapshot = BillService(repository, clock).observeSnapshot().first()

        assertEquals(listOf("edited", "waiting"), snapshot.pendingDrafts.map(DraftSummary::id))
        assertEquals(2, snapshot.overview.pendingDraftCount)
        assertEquals(2, snapshot.overview.hardBlockCount)
        assertEquals(1, snapshot.overview.recentTransactions.size)
        val summary = snapshot.overview.recentTransactions.single()
        assertEquals("active-expense", summary.id)
        assertEquals(TransactionSummaryKind.EXPENSE, summary.kind)
        assertEquals(Money.cny(1_230L), summary.amount)
        assertTrue(summary.canUndo)
    }

    @Test
    fun `account summary funding eligibility rejects credit card income`() {
        val creditSummary = AccountSummary(
            id = "credit",
            name = "TEST CREDIT",
            type = AccountType.LIABILITY_CC,
            displayBalance = Money.cny(0L),
            isLiability = true,
        )
        val bankSummary = creditSummary.copy(
            id = "bank",
            type = AccountType.ASSET_BANK,
            isLiability = false,
        )

        assertTrue(creditSummary.canFund(DraftSummaryKind.EXPENSE))
        assertFalse(creditSummary.canFund(DraftSummaryKind.INCOME))
        assertTrue(bankSummary.canFund(DraftSummaryKind.EXPENSE))
        assertTrue(bankSummary.canFund(DraftSummaryKind.INCOME))
    }

    @Test
    fun `transaction display amount applies one shared expense sign rule`() {
        val expense = TransactionSummary(
            id = "expense-summary",
            title = "TEST EXPENSE",
            supportingText = "fixture",
            amount = Money.cny(1_230L),
            kind = TransactionSummaryKind.EXPENSE,
        )
        val income = expense.copy(
            id = "income-summary",
            kind = TransactionSummaryKind.INCOME,
        )

        assertEquals(Money.cny(-1_230L), expense.signedDisplayAmount())
        assertEquals(Money.cny(1_230L), income.signedDisplayAmount())
    }

    @Test
    fun `void transaction forwards exact identity and audit without sensitive payload`() = runBlocking {
        val repository = FakeLedgerRepository()
        val result = BillService(repository, clock).voidTransaction(
            commandId = CommandId("void-command"),
            transactionId = TransactionId("transaction-to-void"),
        )

        assertEquals(OperationResult.Success("transaction-to-void"), result)
        val call = requireNotNull(repository.voidTransactionCall)
        assertEquals(TransactionId("transaction-to-void"), call.transactionId)
        assertEquals(AuditAction.TRANSACTION_VOIDED, call.auditRecord.action)
        assertEquals("transaction", call.auditRecord.entityType)
        assertEquals("transaction-to-void", call.auditRecord.entityId)
        assertEquals(now, call.auditRecord.occurredAt)
    }

    private fun accountCommand(
        id: String,
        name: String = "TEST ACCOUNT",
        type: AccountType = AccountType.ASSET_BANK,
        openingBalanceText: String = "0",
        currency: CurrencyCode = CurrencyCode.CNY,
    ) = CreateAccountCommand(
        commandId = CommandId(id),
        name = name,
        type = type,
        openingBalanceText = openingBalanceText,
        currency = currency,
    )

    private fun draftCommand(
        id: String,
        type: TransactionType = TransactionType.EXPENSE,
        amountText: String = "25.00",
        counterparty: String = "TEST COUNTERPARTY",
        note: String? = null,
        occurredAt: Instant? = null,
        currency: CurrencyCode = CurrencyCode.CNY,
    ) = CreateManualDraftCommand(
        commandId = CommandId(id),
        type = type,
        amountText = amountText,
        counterparty = counterparty,
        note = note,
        occurredAt = occurredAt,
        currency = currency,
    )

    private fun sourceProposal(
        id: String,
        sourceFamily: SourceFamily = SourceFamily.GENERIC,
    ) = SourceProposalRecord(
        id = id,
        rawEventId = "raw-$id",
        parseAttemptId = "attempt-$id",
        sourceFamily = sourceFamily,
        captureMethod = CaptureMethod.SHARE_TEXT,
        capturedAt = now,
        diagnostic = null,
        candidate = null,
        isPossibleDuplicate = false,
    )

    private fun account(
        id: String,
        type: AccountType,
        createdAt: Instant = now,
        isSystem: Boolean = false,
        isArchived: Boolean = false,
        currency: CurrencyCode = CurrencyCode.CNY,
    ) = LedgerAccount(
        id = AccountId(id),
        name = "TEST $id",
        normalizedName = "test $id",
        type = type,
        currency = currency,
        isSystem = isSystem,
        isArchived = isArchived,
        createdAt = createdAt,
        creationCommandId = CommandId("create-$id"),
    )

    private fun draft(
        id: String,
        type: TransactionType = TransactionType.EXPENSE,
        state: DraftState = DraftState.WAITING_USER,
        fundingAccountId: AccountId? = null,
        updatedAt: Instant = now,
        sourceMode: TransactionSourceMode = TransactionSourceMode.MANUAL,
        currency: CurrencyCode = CurrencyCode.CNY,
    ) = ManualDraft(
        id = DraftId(id),
        state = state,
        type = type,
        amount = Money(2_500L, currency),
        occurredAt = now.minusSeconds(60),
        counterparty = "TEST COUNTERPARTY",
        note = "local fixture",
        fundingAccountId = fundingAccountId,
        createdAt = now.minusSeconds(120),
        updatedAt = updatedAt,
        creationCommandId = CommandId("create-$id"),
        sourceMode = sourceMode,
    )

    private fun transaction(
        id: String,
        type: TransactionType,
        status: dev.bill.core.domain.TransactionStatus,
        amountMinor: Long,
        confirmedAt: Instant,
        draftId: DraftId?,
    ): PostedTransaction {
        val entries = when (type) {
            TransactionType.EXPENSE -> listOf(
                LedgerEntry(AccountId("system-expense"), Money.cny(amountMinor), EntryRole.EXPENSE),
                LedgerEntry(AccountId("fixture-funding"), Money.cny(-amountMinor), EntryRole.FUNDING),
            )

            TransactionType.INCOME -> listOf(
                LedgerEntry(AccountId("fixture-funding"), Money.cny(amountMinor), EntryRole.FUNDING),
                LedgerEntry(AccountId("system-income"), Money.cny(-amountMinor), EntryRole.INCOME),
            )

            else -> error("Test helper only builds income and expense")
        }
        return PostedTransaction(
            id = TransactionId(id),
            draftId = draftId,
            type = type,
            status = status,
            sourceMode = dev.bill.core.domain.TransactionSourceMode.MANUAL,
            occurredAt = confirmedAt.minusSeconds(30),
            confirmedAt = confirmedAt,
            title = "TEST TRANSACTION",
            note = null,
            commandId = CommandId("create-$id"),
            entries = entries,
        )
    }

    private fun ledgerState(
        balances: List<AccountBalance> = emptyList(),
        drafts: List<ManualDraft> = emptyList(),
        transactions: List<PostedTransaction> = emptyList(),
    ) = LedgerState(
        accountBalances = balances,
        pendingDrafts = drafts,
        recentTransactions = transactions,
    )
}

private class FakeSourceReviewRepository(
    initialProposals: List<SourceProposalRecord> = emptyList(),
) : SourceReviewRepository {
    data class CompleteCall(
        val proposalId: String,
        val draft: ReviewDraft,
        val auditRecord: AuditRecord,
    )

    data class DismissCall(
        val proposalId: String,
        val auditRecord: AuditRecord,
    )

    private val proposals = MutableStateFlow(initialProposals)
    var completeCall: CompleteCall? = null
    var dismissCall: DismissCall? = null

    override fun observePendingSourceProposals(): Flow<List<SourceProposalRecord>> = proposals

    override suspend fun completeSourceProposal(
        proposalId: String,
        draft: ReviewDraft,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        completeCall = CompleteCall(proposalId, draft, auditRecord)
        return RepositoryWriteResult(RepositoryWriteStatus.APPLIED, draft.id.value)
    }

    override suspend fun dismissSourceProposal(
        proposalId: String,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        dismissCall = DismissCall(proposalId, auditRecord)
        return RepositoryWriteResult(RepositoryWriteStatus.APPLIED, proposalId)
    }
}

private class FakeLedgerRepository(
    state: LedgerState = LedgerState(emptyList(), emptyList(), emptyList()),
) : LedgerRepository {
    val state = MutableStateFlow(state)

    var createAccountResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)
    var createDraftResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)
    var selectFundingResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)
    var confirmDraftResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)
    var dismissDraftResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)
    var voidTransactionResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)

    var createAccountCall: CreateAccountCall? = null
    var createDraftCall: CreateDraftCall? = null
    var selectFundingCall: SelectFundingCall? = null
    var confirmDraftCall: ConfirmDraftCall? = null
    var dismissDraftCall: DismissDraftCall? = null
    var voidTransactionCall: VoidTransactionCall? = null

    override fun observeState(): Flow<LedgerState> = state

    override suspend fun findAccount(id: AccountId): LedgerAccount? =
        state.value.accountBalances.firstOrNull { it.account.id == id }?.account

    override suspend fun findDraft(id: DraftId): ManualDraft? =
        state.value.pendingDrafts.firstOrNull { it.id == id }

    override suspend fun findTransaction(id: TransactionId): PostedTransaction? =
        state.value.recentTransactions.firstOrNull { it.id == id }

    override suspend fun createAccount(
        account: LedgerAccount,
        openingTransaction: PostedTransaction?,
        auditRecords: List<AuditRecord>,
    ): RepositoryWriteResult {
        createAccountCall = CreateAccountCall(account, openingTransaction, auditRecords)
        if (createAccountResult.status == RepositoryWriteStatus.APPLIED) {
            val openingAmount = openingTransaction
                ?.entries
                ?.firstOrNull { it.accountId == account.id }
                ?.amount
                ?: Money(0L, account.currency)
            state.value = state.value.copy(
                accountBalances = state.value.accountBalances + AccountBalance(account, openingAmount),
                recentTransactions = openingTransaction?.let {
                    state.value.recentTransactions + it
                } ?: state.value.recentTransactions,
            )
        }
        return createAccountResult.withDefaultEntityId(account.id.value)
    }

    override suspend fun createManualDraft(
        draft: ManualDraft,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        createDraftCall = CreateDraftCall(draft, auditRecord)
        if (createDraftResult.status == RepositoryWriteStatus.APPLIED) {
            state.value = state.value.copy(pendingDrafts = state.value.pendingDrafts + draft)
        }
        return createDraftResult.withDefaultEntityId(draft.id.value)
    }

    override suspend fun selectFundingAccount(
        draftId: DraftId,
        accountId: AccountId,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        selectFundingCall = SelectFundingCall(draftId, accountId, auditRecord)
        if (selectFundingResult.status == RepositoryWriteStatus.APPLIED) {
            state.value = state.value.copy(
                pendingDrafts = state.value.pendingDrafts.map { draft ->
                    if (draft.id == draftId) {
                        draft.copy(
                            state = DraftState.EDITED,
                            fundingAccountId = accountId,
                            updatedAt = auditRecord.occurredAt,
                        )
                    } else {
                        draft
                    }
                },
            )
        }
        return selectFundingResult.withDefaultEntityId(draftId.value)
    }

    override suspend fun confirmDraft(
        draftId: DraftId,
        transaction: PostedTransaction,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        confirmDraftCall = ConfirmDraftCall(draftId, transaction, auditRecord)
        if (confirmDraftResult.status == RepositoryWriteStatus.APPLIED) {
            val updatedBalances = state.value.accountBalances.map { accountBalance ->
                val delta = transaction.entries
                    .filter { it.accountId == accountBalance.account.id }
                    .fold(0L) { total, entry -> Math.addExact(total, entry.amount.minorUnits) }
                accountBalance.copy(
                    balance = accountBalance.balance.copy(
                        minorUnits = Math.addExact(accountBalance.balance.minorUnits, delta),
                    ),
                )
            }
            state.value = state.value.copy(
                accountBalances = updatedBalances,
                pendingDrafts = state.value.pendingDrafts.filterNot { it.id == draftId },
                recentTransactions = state.value.recentTransactions + transaction,
            )
        }
        return confirmDraftResult.withDefaultEntityId(transaction.id.value)
    }

    override suspend fun dismissDraft(
        draftId: DraftId,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        dismissDraftCall = DismissDraftCall(draftId, auditRecord)
        if (dismissDraftResult.status == RepositoryWriteStatus.APPLIED) {
            state.value = state.value.copy(
                pendingDrafts = state.value.pendingDrafts.filterNot { it.id == draftId },
            )
        }
        return dismissDraftResult.withDefaultEntityId(draftId.value)
    }

    override suspend fun voidTransaction(
        transactionId: TransactionId,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        voidTransactionCall = VoidTransactionCall(transactionId, auditRecord)
        return voidTransactionResult.withDefaultEntityId(transactionId.value)
    }

    data class CreateAccountCall(
        val account: LedgerAccount,
        val openingTransaction: PostedTransaction?,
        val auditRecords: List<AuditRecord>,
    )

    data class CreateDraftCall(
        val draft: ManualDraft,
        val auditRecord: AuditRecord,
    )

    data class SelectFundingCall(
        val draftId: DraftId,
        val accountId: AccountId,
        val auditRecord: AuditRecord,
    )

    data class ConfirmDraftCall(
        val draftId: DraftId,
        val transaction: PostedTransaction,
        val auditRecord: AuditRecord,
    )

    data class DismissDraftCall(
        val draftId: DraftId,
        val auditRecord: AuditRecord,
    )

    data class VoidTransactionCall(
        val transactionId: TransactionId,
        val auditRecord: AuditRecord,
    )
}

private fun RepositoryWriteResult.withDefaultEntityId(entityId: String): RepositoryWriteResult =
    if (this.entityId == null) copy(entityId = entityId) else this
