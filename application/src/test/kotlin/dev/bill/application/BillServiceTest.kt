package dev.bill.application

import dev.bill.core.domain.AccountBalance
import dev.bill.core.domain.AuditAction
import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.BalanceSnapshot
import dev.bill.core.domain.BalanceSnapshotComparison
import dev.bill.core.domain.BalanceSnapshotId
import dev.bill.core.domain.BalanceSnapshotSourceMode
import dev.bill.core.domain.CommandId
import dev.bill.core.domain.DraftId
import dev.bill.core.domain.DraftState
import dev.bill.core.domain.LedgerAccount
import dev.bill.core.domain.LedgerRepository
import dev.bill.core.domain.LedgerState
import dev.bill.core.domain.InvestmentPosition
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.ObservedChannel
import dev.bill.core.domain.PostedTransaction
import dev.bill.core.domain.ReconciliationDraftRole
import dev.bill.core.domain.ReconciliationKind
import dev.bill.core.domain.ReconciliationResolution
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
import dev.bill.source.contract.GenericDelimitedStatementIdentity
import dev.bill.source.contract.NormalizedCandidate
import dev.bill.source.contract.ObservedEconomicEvent
import dev.bill.source.contract.ObservedMoneyDirection
import dev.bill.source.contract.ObservedTime
import dev.bill.source.contract.SourceFamily
import dev.bill.source.review.SourceProposalRecord
import dev.bill.source.review.SourceReviewRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    fun `user confirmed investment fields create one position snapshot and account`() = runBlocking {
        val repository = FakeLedgerRepository()
        val result = BillService(repository, clock).createInvestmentPosition(
            CreateInvestmentPositionCommand(
                commandId = CommandId("create-position"),
                name = "  示例基金  ",
                instrumentCode = "ab.123",
                currentValueText = "123.45",
                unitsText = "12.345",
                costBasisText = "100.00",
                sourceMode = dev.bill.core.domain.InvestmentPositionSourceMode.OCR,
            ),
        )

        assertEquals(OperationResult.Success("investment:create-position"), result)
        val call = requireNotNull(repository.createInvestmentPositionCall)
        assertEquals("示例基金", call.position.name)
        assertEquals("AB.123", call.position.instrumentCode)
        assertEquals(Money.cny(12_345L), call.position.currentValue)
        assertEquals(BigDecimal("12.345"), call.position.units)
        assertEquals(Money.cny(10_000L), call.position.costBasis)
        assertEquals(AccountType.INVESTMENT_SECURITY, call.account.type)
        assertEquals(call.position.accountId, call.account.id)
        assertEquals(
            listOf(
                AuditAction.ACCOUNT_CREATED,
                AuditAction.OPENING_BALANCE_POSTED,
                AuditAction.INVESTMENT_POSITION_CREATED,
            ),
            call.auditRecords.map(AuditRecord::action),
        )
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
    fun `manual balance snapshot records immutable account evidence without posting`() = runBlocking {
        val bank = account(
            id = "snapshot-bank",
            type = AccountType.ASSET_BANK,
            createdAt = now.minusSeconds(3_600),
        )
        val repository = FakeLedgerRepository(
            ledgerState(balances = listOf(AccountBalance(bank, Money.cny(10_000L)))),
        )

        val result = BillService(repository, clock, localZoneId = ZoneOffset.UTC)
            .createBalanceSnapshot(
                CreateBalanceSnapshotCommand(
                    commandId = CommandId("snapshot-command"),
                    accountId = bank.id,
                    observedBalanceText = "123.45",
                    asOfText = "2026-07-19 07:55",
                    note = "  bank app  ",
                ),
            )

        assertEquals(OperationResult.Success("balance-snapshot:snapshot-command"), result)
        val call = requireNotNull(repository.createBalanceSnapshotCall)
        assertEquals(BalanceSnapshotId("balance-snapshot:snapshot-command"), call.snapshot.id)
        assertEquals(bank.id, call.snapshot.accountId)
        assertEquals(Money.cny(12_345L), call.snapshot.observedBalance)
        assertEquals(Instant.parse("2026-07-19T07:55:00Z"), call.snapshot.asOf)
        assertEquals(now, call.snapshot.recordedAt)
        assertEquals("bank app", call.snapshot.note)
        assertEquals(BalanceSnapshotSourceMode.MANUAL, call.snapshot.sourceMode)
        assertEquals(AuditAction.BALANCE_SNAPSHOT_RECORDED, call.auditRecord.action)
        assertEquals("balance_snapshot", call.auditRecord.entityType)
        assertEquals(call.snapshot.id.value, call.auditRecord.entityId)
        assertTrue(repository.state.value.recentTransactions.isEmpty())
    }

    @Test
    fun `balance snapshot accepts zero but rejects invalid amount time and unsupported account`() = runBlocking {
        val bank = account(
            id = "snapshot-bank",
            type = AccountType.ASSET_BANK,
            createdAt = now.minusSeconds(3_600),
        )
        val investment = account(
            id = "snapshot-investment",
            type = AccountType.INVESTMENT_SECURITY,
            createdAt = now.minusSeconds(3_600),
        )
        val repository = FakeLedgerRepository(
            ledgerState(
                balances = listOf(
                    AccountBalance(bank, Money.cny(0L)),
                    AccountBalance(investment, Money.cny(0L)),
                ),
            ),
        )
        val service = BillService(repository, clock, localZoneId = ZoneOffset.UTC)

        assertEquals(
            OperationResult.Success("balance-snapshot:snapshot-zero"),
            service.createBalanceSnapshot(
                CreateBalanceSnapshotCommand(
                    CommandId("snapshot-zero"),
                    bank.id,
                    "0",
                    "2026-07-19 08:00",
                    null,
                ),
            ),
        )
        listOf("", "-0.01", "1.001", "not-money").forEachIndexed { index, amount ->
            assertEquals(
                OperationResult.Failure(OperationError.INVALID_AMOUNT),
                service.createBalanceSnapshot(
                    CreateBalanceSnapshotCommand(
                        CommandId("snapshot-invalid-amount-$index"),
                        bank.id,
                        amount,
                        "2026-07-19 08:00",
                        null,
                    ),
                ),
            )
        }
        listOf("2026-02-30 08:00", "2026-07-19 08:01", "2026/07/19 08:00").forEachIndexed {
                index,
                asOf,
            ->
            assertEquals(
                OperationResult.Failure(OperationError.INVALID_TIME),
                service.createBalanceSnapshot(
                    CreateBalanceSnapshotCommand(
                        CommandId("snapshot-invalid-time-$index"),
                        bank.id,
                        "1.00",
                        asOf,
                        null,
                    ),
                ),
            )
        }
        assertEquals(
            OperationResult.Failure(OperationError.UNSUPPORTED_ACCOUNT_TYPE),
            service.createBalanceSnapshot(
                CreateBalanceSnapshotCommand(
                    CommandId("snapshot-investment"),
                    investment.id,
                    "1.00",
                    "2026-07-19 08:00",
                    null,
                ),
            ),
        )
        assertEquals(
            OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND),
            service.createBalanceSnapshot(
                CreateBalanceSnapshotCommand(
                    CommandId("snapshot-missing"),
                    AccountId("missing"),
                    "1.00",
                    "2026-07-19 08:00",
                    null,
                ),
            ),
        )
    }

    @Test
    fun `balance snapshot rejects nonexistent and ambiguous local times`() = runBlocking {
        val bank = account(
            id = "snapshot-dst-bank",
            type = AccountType.ASSET_BANK,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        val repository = FakeLedgerRepository(
            ledgerState(balances = listOf(AccountBalance(bank, Money.cny(0L)))),
        )
        val service = BillService(
            repository = repository,
            clock = Clock.fixed(Instant.parse("2026-12-01T12:00:00Z"), ZoneOffset.UTC),
            localZoneId = ZoneId.of("America/New_York"),
        )

        listOf(
            "2026-03-08 02:30",
            "2026-11-01 01:30",
        ).forEachIndexed { index, localTime ->
            assertEquals(
                OperationResult.Failure(OperationError.INVALID_TIME),
                service.createBalanceSnapshot(
                    CreateBalanceSnapshotCommand(
                        CommandId("snapshot-dst-$index"),
                        bank.id,
                        "1.00",
                        localTime,
                        null,
                    ),
                ),
            )
        }
        assertNull(repository.createBalanceSnapshotCall)
    }

    @Test
    fun `snapshot projection uses positive credit card debt and explicit difference status`() = runBlocking {
        val creditCard = account(
            id = "credit-card",
            type = AccountType.LIABILITY_CC,
            createdAt = now.minusSeconds(3_600),
        )
        val snapshot = BalanceSnapshot(
            id = BalanceSnapshotId("balance-snapshot:credit-card"),
            accountId = creditCard.id,
            observedBalance = Money.cny(5_500L),
            asOf = now.minusSeconds(300),
            recordedAt = now,
            note = null,
            sourceMode = BalanceSnapshotSourceMode.MANUAL,
            creationCommandId = CommandId("snapshot-credit-card"),
        )
        val repository = FakeLedgerRepository(
            ledgerState(
                balances = listOf(AccountBalance(creditCard, Money.cny(-5_000L))),
                balanceSnapshots = listOf(
                    BalanceSnapshotComparison(
                        snapshot = snapshot,
                        ledgerBalance = Money.cny(5_000L),
                        difference = Money.cny(500L),
                    ),
                ),
            ),
        )

        val summary = BillService(repository, clock).observeSnapshot().first().accounts.single()

        assertEquals(Money.cny(5_000L), summary.displayBalance)
        val latest = requireNotNull(summary.latestBalanceSnapshot)
        assertEquals(Money.cny(5_500L), latest.observedBalance)
        assertEquals(Money.cny(5_000L), latest.ledgerBalance)
        assertEquals(Money.cny(500L), latest.difference)
        assertEquals(BalanceSnapshotStatus.NEEDS_EXPLANATION, latest.status)
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
    fun `USD drafts reject wallet channels but allow a reviewed bank channel`() = runBlocking {
        val createRepository = FakeLedgerRepository()
        val createService = BillService(createRepository, clock)

        assertEquals(
            OperationResult.Failure(OperationError.UNSUPPORTED_CURRENCY),
            createService.createManualDraft(
                draftCommand(
                    id = "usd-alipay",
                    currency = CurrencyCode.USD,
                ).copy(observedChannel = ObservedChannel.ALIPAY),
            ),
        )
        assertNull(createRepository.createDraftCall)

        val usdDraft = draft(
            id = "usd-review",
            currency = CurrencyCode.USD,
            observedChannel = ObservedChannel.UNKNOWN,
        )
        val editRepository = FakeLedgerRepository(
            ledgerState(drafts = listOf(usdDraft)),
        )
        val editService = BillService(editRepository, clock)
        val baseCommand = UpdateDraftCommand(
            commandId = CommandId("edit-usd-review"),
            draftId = usdDraft.id,
            type = TransactionType.EXPENSE,
            amountText = "25.00",
            counterparty = "TEST COUNTERPARTY",
            note = null,
            occurredAt = now.minusSeconds(60),
            observedChannel = ObservedChannel.ALIPAY,
            fundingAccountId = null,
        )

        assertEquals(
            OperationResult.Failure(OperationError.UNSUPPORTED_CURRENCY),
            editService.updateDraft(baseCommand),
        )
        assertNull(editRepository.updateDraftCall)
        assertEquals(
            OperationResult.Success(usdDraft.id.value),
            editService.updateDraft(
                baseCommand.copy(
                    commandId = CommandId("edit-usd-review-bank"),
                    observedChannel = ObservedChannel.BANK,
                ),
            ),
        )
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
    fun `confirmed fund source requires an existing position and creates only an investment draft`() =
        runBlocking {
            val investment = account("fund-position", AccountType.INVESTMENT_SECURITY)
            val ledgerRepository = FakeLedgerRepository(
                ledgerState(
                    balances = listOf(AccountBalance(investment, Money.cny(10_000L))),
                    positions = listOf(investmentPosition(investment)),
                ),
            )
            val candidate = NormalizedCandidate(
                amount = FieldCandidate(
                    Money.cny(1_234L),
                    0.99,
                    EvidenceLocator.WholePayload,
                ),
                moneyDirection = FieldCandidate(
                    ObservedMoneyDirection.OUTBOUND,
                    0.99,
                    EvidenceLocator.WholePayload,
                ),
                economicEvent = FieldCandidate(
                    ObservedEconomicEvent.INVEST_BUY,
                    0.99,
                    EvidenceLocator.WholePayload,
                ),
            )
            val sourceRepository = FakeSourceReviewRepository(
                initialProposals = listOf(
                    sourceProposal(
                        id = "fund-confirmed",
                        sourceFamily = SourceFamily.ALIPAY,
                        candidate = candidate,
                    ),
                ),
            )
            val service = BillService(ledgerRepository, clock, sourceRepository)

            val review = service.observeSnapshot().first().pendingSourceReviews.single()
            assertEquals(DraftSummaryKind.INVEST_BUY, review.suggestedKind)
            assertEquals(setOf(DraftSummaryKind.INVEST_BUY), review.allowedDraftKinds)
            assertEquals(ObservedChannel.ALIPAY, review.suggestedObservedChannel)

            val result = service.createExternalDraft(
                CreateExternalDraftCommand(
                    commandId = CommandId("fund-external-draft"),
                    proposalId = "fund-confirmed",
                    type = TransactionType.INVEST_BUY,
                    amountText = "12.34",
                    counterparty = investment.name,
                    note = null,
                    investmentAccountId = investment.id,
                ),
            )

            assertEquals(OperationResult.Success("draft:fund-external-draft"), result)
            val draft = requireNotNull(sourceRepository.completeCall).draft
            assertEquals(TransactionType.INVEST_BUY, draft.type)
            assertEquals(investment.id, draft.investmentAccountId)
            assertEquals(TransactionSourceMode.EXTERNAL, draft.sourceMode)
        }

    @Test
    fun `confirmed fund source rejects ordinary drafts and missing position targets`() = runBlocking {
        val candidate = NormalizedCandidate(
            economicEvent = FieldCandidate(
                ObservedEconomicEvent.INVEST_BUY,
                0.99,
                EvidenceLocator.WholePayload,
            ),
        )
        val sourceRepository = FakeSourceReviewRepository(
            initialProposals = listOf(
                sourceProposal(
                    id = "fund-confirmed",
                    sourceFamily = SourceFamily.ALIPAY,
                    candidate = candidate,
                ),
            ),
        )
        val service = BillService(FakeLedgerRepository(), clock, sourceRepository)

        assertEquals(
            OperationResult.Failure(OperationError.INVALID_STATE),
            service.createExternalDraft(
                CreateExternalDraftCommand(
                    commandId = CommandId("wrong-kind"),
                    proposalId = "fund-confirmed",
                    type = TransactionType.EXPENSE,
                    amountText = "12.34",
                    counterparty = "Example",
                    note = null,
                ),
            ),
        )
        assertEquals(
            OperationResult.Failure(OperationError.ACCOUNT_REQUIRED),
            service.createExternalDraft(
                CreateExternalDraftCommand(
                    commandId = CommandId("missing-target"),
                    proposalId = "fund-confirmed",
                    type = TransactionType.INVEST_BUY,
                    amountText = "12.34",
                    counterparty = "Example",
                    note = null,
                ),
            ),
        )
        val orphanInvestmentAccount = account(
            id = "orphan-investment-account",
            type = AccountType.INVESTMENT_SECURITY,
        )
        val orphanService = BillService(
            FakeLedgerRepository(
                ledgerState(
                    balances = listOf(
                        AccountBalance(orphanInvestmentAccount, Money.cny(10_000L)),
                    ),
                ),
            ),
            clock,
            sourceRepository,
        )
        assertEquals(
            OperationResult.Failure(OperationError.INVALID_STATE),
            orphanService.createExternalDraft(
                CreateExternalDraftCommand(
                    commandId = CommandId("orphan-target"),
                    proposalId = "fund-confirmed",
                    type = TransactionType.INVEST_BUY,
                    amountText = "12.34",
                    counterparty = "Example",
                    note = null,
                    investmentAccountId = orphanInvestmentAccount.id,
                ),
            ),
        )
        assertNull(sourceRepository.completeCall)
    }

    @Test
    fun `investment draft confirmation posts a balanced asset transfer`() = runBlocking {
        val bank = account("bank", AccountType.ASSET_BANK)
        val investment = account("fund-position", AccountType.INVESTMENT_SECURITY)
        val pending = draft(
            id = "invest-buy",
            type = TransactionType.INVEST_BUY,
            fundingAccountId = bank.id,
            investmentAccountId = investment.id,
            sourceMode = TransactionSourceMode.EXTERNAL,
        )
        val repository = FakeLedgerRepository(
            ledgerState(
                balances = listOf(
                    AccountBalance(bank, Money.cny(50_000L)),
                    AccountBalance(investment, Money.cny(10_000L)),
                ),
                drafts = listOf(pending),
                positions = listOf(investmentPosition(investment)),
            ),
        )

        val result = BillService(repository, clock).confirmDraft(
            CommandId("confirm-invest-buy"),
            pending.id,
        )

        assertEquals(
            OperationResult.Success("transaction:confirm:confirm-invest-buy"),
            result,
        )
        val transaction = requireNotNull(repository.confirmDraftCall).transaction
        assertEquals(TransactionType.INVEST_BUY, transaction.type)
        assertEquals(-2_500L, transaction.entries.single { it.accountId == bank.id }.amount.minorUnits)
        assertEquals(
            2_500L,
            transaction.entries.single { it.accountId == investment.id }.amount.minorUnits,
        )
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
    fun `snapshot distinguishes mapped statement rows from opaque text files`() = runBlocking {
        val sourceRepository = FakeSourceReviewRepository(
            initialProposals = listOf(
                SourceProposalRecord(
                    id = "proposal-mapped-row",
                    rawEventId = "raw-mapped-row",
                    parseAttemptId = "attempt-mapped-row",
                    parserId = GenericDelimitedStatementIdentity.PARSER_ID,
                    providerId = GenericDelimitedStatementIdentity.PROVIDER_ID,
                    connectorId = GenericDelimitedStatementIdentity.CONNECTOR_ID,
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
            SourceReviewKind.DELIMITED_STATEMENT_ROW,
            snapshot.pendingSourceReviews.single().kind,
        )
    }

    @Test
    fun `notification review resolves only a safe local route label`() = runBlocking {
        val sourceRepository = FakeSourceReviewRepository(
            initialProposals = listOf(
                SourceProposalRecord(
                    id = "proposal-notification",
                    rawEventId = "raw-notification",
                    parseAttemptId = "attempt-notification",
                    parserId = "fixture-payment",
                    providerId = "fixture-provider",
                    connectorId = "fixture-payment",
                    sourceFamily = SourceFamily.BANK,
                    captureMethod = CaptureMethod.NOTIFICATION,
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
            notificationRouteLabelResolver = NotificationRouteLabelResolver { connectorId ->
                "Fixture bank notification".takeIf { connectorId == "fixture-payment" }
            },
        ).observeSnapshot().first()

        val review = snapshot.pendingSourceReviews.single()
        assertEquals(SourceReviewKind.NOTIFICATION, review.kind)
        assertEquals("Fixture bank notification", review.notificationRouteLabel)
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
                        occurredAt = FieldCandidate(
                            value = ObservedTime.DateOnly(LocalDate.of(2026, 7, 18)),
                            confidence = 0.9,
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
            localZoneId = ZoneId.of("Asia/Shanghai"),
        ).observeSnapshot().first()
        val review = snapshot.pendingSourceReviews.single()

        assertEquals(SourceReviewKind.PHOTO_OCR, review.kind)
        assertEquals(DraftSummaryKind.INCOME, review.suggestedKind)
        assertEquals(6_600L, review.suggestedAmount?.minorUnits)
        assertEquals(Instant.parse("2026-07-17T16:00:00Z"), review.suggestedOccurredAt)
        assertEquals("测试付款方", review.suggestedCounterparty)
        assertEquals(ObservedChannel.UNKNOWN, review.suggestedObservedChannel)
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
    fun `snapshot proposes only hard-gated transfers and repayments without auto posting`() =
        runBlocking {
            val bank = account("bank", AccountType.ASSET_BANK)
            val wallet = account("wallet", AccountType.ASSET_EWALLET_BALANCE)
            val card = account("card", AccountType.LIABILITY_CC)
            val outbound = draft(
                id = "transfer-out",
                type = TransactionType.EXPENSE,
                fundingAccountId = bank.id,
            )
            val inbound = draft(
                id = "transfer-in",
                type = TransactionType.INCOME,
                fundingAccountId = wallet.id,
            )
            val sameAccountInbound = draft(
                id = "same-account-in",
                type = TransactionType.INCOME,
                fundingAccountId = bank.id,
            )
            val repository = FakeLedgerRepository(
                ledgerState(
                    balances = listOf(
                        AccountBalance(bank, Money.cny(20_000L)),
                        AccountBalance(wallet, Money.cny(0L)),
                        AccountBalance(card, Money.cny(-8_000L)),
                    ),
                    drafts = listOf(outbound, inbound, sameAccountInbound),
                ),
            )

            val snapshot = BillService(repository, clock).observeSnapshot().first()

            assertTrue(snapshot.reconciliationCases.any {
                it.kind == ReconciliationCaseKind.TRANSFER &&
                    it.draftIds == listOf("transfer-out", "transfer-in")
            })
            assertFalse(snapshot.reconciliationCases.any {
                it.kind == ReconciliationCaseKind.TRANSFER &&
                    "same-account-in" in it.draftIds
            })
            assertTrue(snapshot.reconciliationCases.any {
                it.kind == ReconciliationCaseKind.LIABILITY_REPAYMENT &&
                    it.draftIds == listOf("transfer-out") &&
                    it.destinationAccountId == "card"
            })
            assertNull(repository.resolveReconciliationCall)
        }

    @Test
    fun `draft edit changes review facts but preserves identity and source mode`() = runBlocking {
        val bank = account("bank", AccountType.ASSET_BANK)
        val original = draft(
            id = "ocr-draft",
            sourceMode = TransactionSourceMode.EXTERNAL,
            observedChannel = ObservedChannel.UNKNOWN,
        )
        val repository = FakeLedgerRepository(
            ledgerState(
                balances = listOf(AccountBalance(bank, Money.cny(20_000L))),
                drafts = listOf(original),
            ),
        )
        val service = BillService(repository, clock)
        val command = UpdateDraftCommand(
            commandId = CommandId("edit-ocr-draft"),
            draftId = original.id,
            type = TransactionType.EXPENSE,
            amountText = "13.70",
            counterparty = "  测试   商户  ",
            note = " corrected locally ",
            occurredAt = now.minusSeconds(30),
            observedChannel = ObservedChannel.ALIPAY,
            fundingAccountId = bank.id,
        )
        val result = service.updateDraft(command)

        assertEquals(OperationResult.Success(original.id.value), result)
        val call = requireNotNull(repository.updateDraftCall)
        assertEquals(original.id, call.draft.id)
        assertEquals(original.creationCommandId, call.draft.creationCommandId)
        assertEquals(original.createdAt, call.draft.createdAt)
        assertEquals(TransactionSourceMode.EXTERNAL, call.draft.sourceMode)
        assertEquals(ObservedChannel.ALIPAY, call.draft.observedChannel)
        assertEquals(Money.cny(1_370L), call.draft.amount)
        assertEquals("测试 商户", call.draft.counterparty)
        assertEquals("corrected locally", call.draft.note)
        assertEquals(bank.id, call.draft.fundingAccountId)
        assertEquals(AuditAction.DRAFT_EDITED, call.auditRecord.action)

        repository.state.value = repository.state.value.copy(
            pendingDrafts = listOf(call.draft.copy(state = DraftState.LINKED)),
        )
        repository.updateDraftResult = RepositoryWriteResult(
            RepositoryWriteStatus.ALREADY_APPLIED,
        )
        assertEquals(
            OperationResult.Success(original.id.value),
            service.updateDraft(command),
        )
    }

    @Test
    fun `draft edit rejects future time before repository write`() = runBlocking {
        val repository = FakeLedgerRepository(
            ledgerState(drafts = listOf(draft(id = "future-draft"))),
        )

        val result = BillService(repository, clock).updateDraft(
            UpdateDraftCommand(
                commandId = CommandId("edit-future"),
                draftId = DraftId("future-draft"),
                type = TransactionType.EXPENSE,
                amountText = "25.00",
                counterparty = "TEST COUNTERPARTY",
                note = null,
                occurredAt = now.plusSeconds(1),
                observedChannel = ObservedChannel.OTHER,
                fundingAccountId = null,
            ),
        )

        assertEquals(OperationResult.Failure(OperationError.INVALID_TIME), result)
        assertNull(repository.updateDraftCall)
    }

    @Test
    fun `funded by requires reviewed channels matching merchant account amount and time`() =
        runBlocking {
            val bank = account("bank", AccountType.ASSET_BANK)
            val channel = draft(
                id = "alipay-ocr",
                fundingAccountId = bank.id,
                sourceMode = TransactionSourceMode.EXTERNAL,
                observedChannel = ObservedChannel.ALIPAY,
                counterparty = "Cotti Coffee",
                amountMinor = 990L,
                occurredAt = now.minusSeconds(120),
            )
            val bankEvidence = draft(
                id = "bank-notification",
                fundingAccountId = bank.id,
                sourceMode = TransactionSourceMode.EXTERNAL,
                observedChannel = ObservedChannel.BANK,
                counterparty = "cotti   coffee",
                amountMinor = 990L,
                occurredAt = now.minusSeconds(60),
            )
            val stale = bankEvidence.copy(
                id = DraftId("stale-bank"),
                occurredAt = now.minusSeconds(2_001),
                creationCommandId = CommandId("create-stale-bank"),
            )
            val wrongMerchant = bankEvidence.copy(
                id = DraftId("wrong-merchant"),
                counterparty = "Another merchant",
                creationCommandId = CommandId("create-wrong-merchant"),
            )
            val repository = FakeLedgerRepository(
                ledgerState(
                    balances = listOf(AccountBalance(bank, Money.cny(20_000L))),
                    drafts = listOf(channel, bankEvidence, stale, wrongMerchant),
                ),
            )
            val service = BillService(repository, clock)

            val cases = service.observeSnapshot().first().reconciliationCases
                .filter { it.kind == ReconciliationCaseKind.FUNDED_BY }

            val case = cases.single()
            assertEquals(listOf("alipay-ocr", "bank-notification"), case.draftIds)
            assertEquals("bank", case.sourceAccountId)
            val result = service.resolveReconciliation(
                ResolveReconciliationCommand(CommandId("reconcile-funded-by"), case.id),
            )
            assertTrue(result is OperationResult.Success)
            val resolution = requireNotNull(repository.resolveReconciliationCall).resolution
            assertEquals(ReconciliationKind.FUNDED_BY, resolution.kind)
            assertEquals(TransactionType.EXPENSE, resolution.transaction.type)
            assertEquals(
                setOf(
                    ReconciliationDraftRole.FUNDED_CHANNEL_EXPENSE,
                    ReconciliationDraftRole.FUNDED_BANK_EVIDENCE,
                ),
                resolution.draftLinks.mapTo(mutableSetOf()) { it.role },
            )
            assertTrue(resolution.relations.isEmpty())
            assertEquals(0L, resolution.transaction.entries.sumOf { it.amount.minorUnits })
        }

    @Test
    fun `funded by never treats wallet balance as the bank funding account`() = runBlocking {
        val wallet = account("wallet", AccountType.ASSET_EWALLET_BALANCE)
        val drafts = listOf(
            draft(
                id = "wechat-ocr",
                fundingAccountId = wallet.id,
                sourceMode = TransactionSourceMode.EXTERNAL,
                observedChannel = ObservedChannel.WECHAT,
            ),
            draft(
                id = "bank-evidence",
                fundingAccountId = wallet.id,
                sourceMode = TransactionSourceMode.EXTERNAL,
                observedChannel = ObservedChannel.BANK,
            ),
        )
        val repository = FakeLedgerRepository(
            ledgerState(
                balances = listOf(AccountBalance(wallet, Money.cny(20_000L))),
                drafts = drafts,
            ),
        )

        assertFalse(
            BillService(repository, clock).observeSnapshot().first().reconciliationCases.any {
                it.kind == ReconciliationCaseKind.FUNDED_BY
            },
        )
    }

    @Test
    fun `confirmed transfer sends one balanced replacement posting with both draft links`() =
        runBlocking {
            val bank = account("bank", AccountType.ASSET_BANK)
            val wallet = account("wallet", AccountType.ASSET_EWALLET_BALANCE)
            val repository = FakeLedgerRepository(
                ledgerState(
                    balances = listOf(
                        AccountBalance(bank, Money.cny(20_000L)),
                        AccountBalance(wallet, Money.cny(0L)),
                    ),
                    drafts = listOf(
                        draft(
                            id = "transfer-out",
                            type = TransactionType.EXPENSE,
                            fundingAccountId = bank.id,
                        ),
                        draft(
                            id = "transfer-in",
                            type = TransactionType.INCOME,
                            fundingAccountId = wallet.id,
                        ),
                    ),
                ),
            )
            val service = BillService(repository, clock)
            val case = service.observeSnapshot().first().reconciliationCases.single()

            val result = service.resolveReconciliation(
                ResolveReconciliationCommand(CommandId("reconcile-transfer"), case.id),
            )

            assertTrue(result is OperationResult.Success)
            val call = requireNotNull(repository.resolveReconciliationCall)
            assertEquals(ReconciliationKind.TRANSFER_PAIR, call.resolution.kind)
            assertEquals(TransactionType.TRANSFER, call.resolution.transaction.type)
            assertEquals(0L, call.resolution.transaction.entries.sumOf { it.amount.minorUnits })
            assertEquals(
                setOf(
                    ReconciliationDraftRole.TRANSFER_OUTBOUND,
                    ReconciliationDraftRole.TRANSFER_INBOUND,
                ),
                call.resolution.draftLinks.map { it.role }.toSet(),
            )
            assertTrue(call.resolution.relations.isEmpty())
            assertEquals(AuditAction.RECONCILIATION_CONFIRMED, call.auditRecord.action)
        }

    @Test
    fun `refund suggestion respects prior refunds and confirmation links the original expense`() =
        runBlocking {
            val bank = account("fixture-funding", AccountType.ASSET_BANK)
            val original = transaction(
                id = "original-expense",
                type = TransactionType.EXPENSE,
                status = dev.bill.core.domain.TransactionStatus.ACTIVE,
                amountMinor = 5_000L,
                confirmedAt = now.minusSeconds(3_000),
                draftId = DraftId("original-draft"),
            )
            val inbound = draft(
                id = "refund-in",
                type = TransactionType.INCOME,
                fundingAccountId = bank.id,
                amountMinor = 2_500L,
                occurredAt = now.minusSeconds(60),
            )
            val repository = FakeLedgerRepository(
                ledgerState(
                    balances = listOf(AccountBalance(bank, Money.cny(20_000L))),
                    drafts = listOf(inbound),
                    transactions = listOf(original),
                    activeRefundTotals = mapOf(original.id to Money.cny(1_000L)),
                ),
            )
            val service = BillService(repository, clock)
            val case = service.observeSnapshot().first().reconciliationCases.single()

            assertEquals(ReconciliationCaseKind.REFUND, case.kind)
            assertEquals(original.id.value, case.relatedTransactionId)
            val result = service.resolveReconciliation(
                ResolveReconciliationCommand(CommandId("reconcile-refund"), case.id),
            )

            assertTrue(result is OperationResult.Success)
            val resolution = requireNotNull(repository.resolveReconciliationCall).resolution
            assertEquals(ReconciliationKind.REFUND, resolution.kind)
            assertEquals(TransactionType.REFUND, resolution.transaction.type)
            assertEquals(original.id, resolution.relations.single().toTransactionId)
            assertEquals(
                ReconciliationDraftRole.REFUND_INBOUND,
                resolution.draftLinks.single().role,
            )

            repository.state.value = repository.state.value.copy(
                activeRefundTotals = mapOf(original.id to Money.cny(3_000L)),
            )
            assertTrue(service.observeSnapshot().first().reconciliationCases.isEmpty())
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
        candidate: NormalizedCandidate? = null,
    ) = SourceProposalRecord(
        id = id,
        rawEventId = "raw-$id",
        parseAttemptId = "attempt-$id",
        sourceFamily = sourceFamily,
        captureMethod = CaptureMethod.SHARE_TEXT,
        capturedAt = now,
        diagnostic = null,
        candidate = candidate,
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
        investmentAccountId: AccountId? = if (type == TransactionType.INVEST_BUY) {
            AccountId("fund-position")
        } else {
            null
        },
        updatedAt: Instant = now,
        sourceMode: TransactionSourceMode = TransactionSourceMode.MANUAL,
        currency: CurrencyCode = CurrencyCode.CNY,
        amountMinor: Long = 2_500L,
        occurredAt: Instant = now.minusSeconds(60),
        observedChannel: ObservedChannel = ObservedChannel.UNKNOWN,
        counterparty: String = "TEST COUNTERPARTY",
    ) = ManualDraft(
        id = DraftId(id),
        state = state,
        type = type,
        amount = Money(amountMinor, currency),
        occurredAt = occurredAt,
        counterparty = counterparty,
        note = "local fixture",
        fundingAccountId = fundingAccountId,
        investmentAccountId = investmentAccountId,
        createdAt = now.minusSeconds(120),
        updatedAt = updatedAt,
        creationCommandId = CommandId("create-$id"),
        sourceMode = sourceMode,
        observedChannel = observedChannel,
    )

    private fun investmentPosition(account: LedgerAccount) = InvestmentPosition(
        id = dev.bill.core.domain.InvestmentPositionId("position:${account.id.value}"),
        accountId = account.id,
        instrumentCode = null,
        name = account.name,
        currentValue = Money.cny(10_000L),
        units = null,
        costBasis = null,
        asOf = now,
        sourceMode = dev.bill.core.domain.InvestmentPositionSourceMode.MANUAL,
        createdAt = now,
        updatedAt = now,
        creationCommandId = account.creationCommandId,
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
        activeRefundTotals: Map<TransactionId, Money> = emptyMap(),
        positions: List<InvestmentPosition> = emptyList(),
        balanceSnapshots: List<BalanceSnapshotComparison> = emptyList(),
    ) = LedgerState(
        accountBalances = balances,
        pendingDrafts = drafts,
        recentTransactions = transactions,
        activeRefundTotals = activeRefundTotals,
        investmentPositions = positions,
        balanceSnapshotComparisons = balanceSnapshots,
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
    var createInvestmentPositionResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)
    var createBalanceSnapshotResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)
    var createDraftResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)
    var updateDraftResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)
    var selectFundingResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)
    var confirmDraftResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)
    var dismissDraftResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)
    var resolveReconciliationResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)
    var voidTransactionResult = RepositoryWriteResult(RepositoryWriteStatus.APPLIED)

    var createAccountCall: CreateAccountCall? = null
    var createInvestmentPositionCall: CreateInvestmentPositionCall? = null
    var createBalanceSnapshotCall: CreateBalanceSnapshotCall? = null
    var createDraftCall: CreateDraftCall? = null
    var updateDraftCall: UpdateDraftCall? = null
    var selectFundingCall: SelectFundingCall? = null
    var confirmDraftCall: ConfirmDraftCall? = null
    var dismissDraftCall: DismissDraftCall? = null
    var resolveReconciliationCall: ResolveReconciliationCall? = null
    var voidTransactionCall: VoidTransactionCall? = null

    override fun observeState(): Flow<LedgerState> = state

    override suspend fun findAccount(id: AccountId): LedgerAccount? =
        state.value.accountBalances.firstOrNull { it.account.id == id }?.account

    override suspend fun findInvestmentPositionByAccountId(
        accountId: AccountId,
    ): InvestmentPosition? = state.value.investmentPositions.firstOrNull {
        it.accountId == accountId
    }

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

    override suspend fun createInvestmentPosition(
        position: InvestmentPosition,
        account: LedgerAccount,
        openingTransaction: PostedTransaction,
        auditRecords: List<AuditRecord>,
    ): RepositoryWriteResult {
        createInvestmentPositionCall = CreateInvestmentPositionCall(
            position,
            account,
            openingTransaction,
            auditRecords,
        )
        if (createInvestmentPositionResult.status == RepositoryWriteStatus.APPLIED) {
            state.value = state.value.copy(
                accountBalances = state.value.accountBalances + AccountBalance(
                    account,
                    position.currentValue,
                ),
                investmentPositions = state.value.investmentPositions + position,
                recentTransactions = state.value.recentTransactions + openingTransaction,
            )
        }
        return createInvestmentPositionResult.withDefaultEntityId(position.id.value)
    }

    override suspend fun createBalanceSnapshot(
        snapshot: BalanceSnapshot,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        createBalanceSnapshotCall = CreateBalanceSnapshotCall(snapshot, auditRecord)
        return createBalanceSnapshotResult.withDefaultEntityId(snapshot.id.value)
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

    override suspend fun updateDraft(
        draft: ReviewDraft,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        updateDraftCall = UpdateDraftCall(draft, auditRecord)
        if (updateDraftResult.status == RepositoryWriteStatus.APPLIED) {
            state.value = state.value.copy(
                pendingDrafts = state.value.pendingDrafts.map { existing ->
                    if (existing.id == draft.id) draft else existing
                },
            )
        }
        return updateDraftResult.withDefaultEntityId(draft.id.value)
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

    override suspend fun activeRefundTotal(originalTransactionId: TransactionId): Money? {
        val original = state.value.recentTransactions.firstOrNull {
            it.id == originalTransactionId
        } ?: return null
        val currency = original.entries.firstOrNull()?.amount?.currency ?: return null
        return state.value.activeRefundTotals[originalTransactionId] ?: Money(0L, currency)
    }

    override suspend fun resolveReconciliation(
        resolution: ReconciliationResolution,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        resolveReconciliationCall = ResolveReconciliationCall(resolution, auditRecord)
        return resolveReconciliationResult.withDefaultEntityId(resolution.transaction.id.value)
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

    data class CreateInvestmentPositionCall(
        val position: InvestmentPosition,
        val account: LedgerAccount,
        val openingTransaction: PostedTransaction,
        val auditRecords: List<AuditRecord>,
    )

    data class CreateBalanceSnapshotCall(
        val snapshot: BalanceSnapshot,
        val auditRecord: AuditRecord,
    )

    data class CreateDraftCall(
        val draft: ManualDraft,
        val auditRecord: AuditRecord,
    )

    data class UpdateDraftCall(
        val draft: ReviewDraft,
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

    data class ResolveReconciliationCall(
        val resolution: ReconciliationResolution,
        val auditRecord: AuditRecord,
    )

    data class VoidTransactionCall(
        val transactionId: TransactionId,
        val auditRecord: AuditRecord,
    )
}

private fun RepositoryWriteResult.withDefaultEntityId(entityId: String): RepositoryWriteResult =
    if (this.entityId == null) copy(entityId = entityId) else this
