package dev.bill.source.pipeline

import dev.bill.core.model.Money
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.EvidenceLocator
import dev.bill.source.contract.EvidenceReadResult
import dev.bill.source.contract.EvidenceReader
import dev.bill.source.contract.FieldCandidate
import dev.bill.source.contract.NormalizedCandidate
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.ParserId
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventAppendResult
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.RawEventRepository
import dev.bill.source.contract.SafeDiagnostic
import dev.bill.source.contract.SourceCapability
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.SourceParser
import dev.bill.source.contract.VersionId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SourceIngestionServiceTest {
    @Test
    fun `parsed candidates are committed only as waiting-user draft proposals`() = runTest {
        val fixture = Fixture(parseResult = ParseResult.Parsed(candidate()))

        val result = fixture.service.ingest(fixture.rawEvent)

        val recorded = assertRecorded(result)
        assertEquals(RawEventDisposition.INSERTED, recorded.rawEventDisposition)
        assertEquals(ParseAttemptOutcome.PARSED, recorded.outcome)
        assertEquals(DraftProposalReviewState.WAITING_USER, recorded.draftProposalState)
        assertNull(recorded.diagnostic)
        assertEquals(listOf(MAX_EVIDENCE_BYTES), fixture.evidenceReader.limits)
        assertEquals(1, fixture.parser.parseCalls)

        val committed = fixture.commitStore.commits.single()
        assertEquals(ParseAttemptOutcome.PARSED, committed.attempt.outcome)
        assertEquals(DraftProposalReviewState.WAITING_USER, committed.draft?.reviewState)
        assertEquals(candidate(), committed.draft?.candidate)
        assertEquals(committed.attempt.id, committed.draft?.parseAttemptId)
        assertEquals(fixture.rawEvent.id, committed.draft?.rawEventId)
    }

    @Test
    fun `needs-review candidates are committed only as waiting-user draft proposals`() = runTest {
        val diagnostic = SafeDiagnostic(
            code = DiagnosticCode.INSUFFICIENT_FIELDS,
            recoverable = true,
        )
        val fixture = Fixture(
            parseResult = ParseResult.NeedsUserReview(
                candidate = candidate(),
                diagnostic = diagnostic,
            ),
        )

        val result = fixture.service.ingest(fixture.rawEvent)

        val recorded = assertRecorded(result)
        assertEquals(ParseAttemptOutcome.NEEDS_USER_REVIEW, recorded.outcome)
        assertEquals(DraftProposalReviewState.WAITING_USER, recorded.draftProposalState)
        assertEquals(diagnostic, recorded.diagnostic)
        assertEquals(DraftProposalReviewState.WAITING_USER, fixture.commitStore.commits.single().draft?.reviewState)
    }

    @Test
    fun `needs-review without observed fields still creates an empty waiting-user work item`() =
        runTest {
            val fixture = Fixture(
                parseResult = ParseResult.NeedsUserReview(
                    candidate = null,
                    diagnostic = SafeDiagnostic(
                        code = DiagnosticCode.INSUFFICIENT_FIELDS,
                        recoverable = true,
                    ),
                ),
            )

            val result = fixture.service.ingest(fixture.rawEvent)

            val recorded = assertRecorded(result)
            assertEquals(ParseAttemptOutcome.NEEDS_USER_REVIEW, recorded.outcome)
            assertEquals(
                DraftProposalReviewState.WAITING_USER,
                recorded.draftProposalState,
            )
            val draftProposal = requireNotNull(fixture.commitStore.commits.single().draft)
            assertNull(draftProposal.candidate)
        }

    @Test
    fun `parser rejection records an attempt and never creates a draft`() = runTest {
        val fixture = Fixture(
            parseResult = rejected(DiagnosticCode.MALFORMED_EVIDENCE, recoverable = false),
        )

        val result = fixture.service.ingest(fixture.rawEvent)

        val recorded = assertRecorded(result)
        assertEquals(ParseAttemptOutcome.REJECTED, recorded.outcome)
        assertEquals(DiagnosticCode.MALFORMED_EVIDENCE, recorded.diagnostic?.code)
        assertNull(recorded.draftProposalState)
        assertNull(fixture.commitStore.commits.single().draft)
    }

    @Test
    fun `missing evidence records a safe rejected attempt after parser selection`() = runTest {
        val fixture = Fixture(evidenceResult = { EvidenceReadResult.NotFound })

        val result = fixture.service.ingest(fixture.rawEvent)

        assertSafeRecordedRejection(
            fixture = fixture,
            result = result,
            expectedCode = DiagnosticCode.EVIDENCE_NOT_FOUND,
            expectedRecoverable = true,
        )
        assertEquals(0, fixture.parser.parseCalls)
    }

    @Test
    fun `evidence read failure records a generic safe rejected attempt`() = runTest {
        val fixture = Fixture(
            evidenceResult = {
                EvidenceReadResult.Failed(
                    SafeDiagnostic(
                        code = DiagnosticCode.MALFORMED_EVIDENCE,
                        recoverable = false,
                    ),
                )
            },
        )

        val result = fixture.service.ingest(fixture.rawEvent)

        assertSafeRecordedRejection(
            fixture = fixture,
            result = result,
            expectedCode = DiagnosticCode.EVIDENCE_READ_FAILED,
            expectedRecoverable = false,
        )
        assertEquals(0, fixture.parser.parseCalls)
    }

    @Test
    fun `evidence reader runtime exception is sanitized and records a rejected attempt`() = runTest {
        val sensitiveMessage = "private-evidence-path-and-account"
        val fixture = Fixture(
            evidenceResult = { throw IllegalStateException(sensitiveMessage) },
        )

        val result = fixture.service.ingest(fixture.rawEvent)

        assertSafeRecordedRejection(
            fixture = fixture,
            result = result,
            expectedCode = DiagnosticCode.EVIDENCE_READ_FAILED,
            expectedRecoverable = true,
        )
        assertFalse(result.toString().contains(sensitiveMessage))
        assertFalse(fixture.commitStore.commits.single().attempt.toString().contains(sensitiveMessage))
    }

    @Test
    fun `evidence larger than the requested bound is rejected by a second size check`() = runTest {
        val oversizedBytes = ByteArray(MAX_EVIDENCE_BYTES.toInt() + 1) { 7 }
        val fixture = Fixture(
            evidenceBytes = oversizedBytes,
            evidenceResult = {
                EvidenceReadResult.Found(EvidenceInput("application/octet-stream", oversizedBytes))
            },
        )

        val result = fixture.service.ingest(fixture.rawEvent)

        assertEquals(listOf(MAX_EVIDENCE_BYTES), fixture.evidenceReader.limits)
        assertSafeRecordedRejection(
            fixture = fixture,
            result = result,
            expectedCode = DiagnosticCode.EVIDENCE_TOO_LARGE,
            expectedRecoverable = true,
        )
        assertEquals(0, fixture.parser.parseCalls)
    }

    @Test
    fun `content hash mismatch records a non-recoverable rejected attempt`() = runTest {
        val expectedBytes = "expected evidence".toByteArray()
        val substitutedBytes = "substituted evidence".toByteArray()
        val fixture = Fixture(
            evidenceBytes = expectedBytes,
            evidenceResult = {
                EvidenceReadResult.Found(EvidenceInput("text/plain", substitutedBytes))
            },
        )

        val result = fixture.service.ingest(fixture.rawEvent)

        assertSafeRecordedRejection(
            fixture = fixture,
            result = result,
            expectedCode = DiagnosticCode.EVIDENCE_INTEGRITY_MISMATCH,
            expectedRecoverable = false,
        )
        assertEquals(0, fixture.parser.parseCalls)
    }

    @Test
    fun `parser runtime exception is sanitized and records a rejected attempt`() = runTest {
        val sensitiveMessage = "card-number-from-payload"
        val fixture = Fixture(
            parserHandler = { _, _ -> throw IllegalArgumentException(sensitiveMessage) },
        )

        val result = fixture.service.ingest(fixture.rawEvent)

        assertSafeRecordedRejection(
            fixture = fixture,
            result = result,
            expectedCode = DiagnosticCode.PARSER_RUNTIME_FAILURE,
            expectedRecoverable = true,
        )
        assertFalse(result.toString().contains(sensitiveMessage))
        assertFalse(fixture.commitStore.commits.single().attempt.toString().contains(sensitiveMessage))
    }

    @Test
    fun `raw event id collision fails before parser resolution or evidence access`() = runTest {
        val repository = StubRawEventRepository(RawEventAppendResult.IdCollision)
        val fixture = Fixture(rawEventRepository = repository)

        val result = fixture.service.ingest(fixture.rawEvent)

        val failed = assertFailed(result)
        assertEquals(IngestionFailure.RAW_EVENT_ID_COLLISION, failed.failure)
        assertEquals(DiagnosticCode.RAW_EVENT_ID_COLLISION, failed.diagnostic.code)
        assertNull(failed.rawEventDisposition)
        assertNull(failed.attemptId)
        assertTrue(fixture.evidenceReader.limits.isEmpty())
        assertTrue(fixture.commitStore.commits.isEmpty())
    }

    @Test
    fun `no matching parser fails before evidence access and cannot fabricate an attempt`() = runTest {
        val fixture = Fixture(
            parserRegistry = ParserRegistry(emptyList()),
        )

        val result = fixture.service.ingest(fixture.rawEvent)

        val failed = assertFailed(result)
        assertEquals(IngestionFailure.NO_MATCHING_PARSER, failed.failure)
        assertEquals(RawEventDisposition.INSERTED, failed.rawEventDisposition)
        assertNull(failed.attemptId)
        assertTrue(fixture.evidenceReader.limits.isEmpty())
        assertTrue(fixture.commitStore.commits.isEmpty())
    }

    @Test
    fun `ambiguous parser selection fails closed before evidence access`() = runTest {
        val first = StubParser(identity(parserId = "parser-one")) { _, _ ->
            ParseResult.Parsed(candidate())
        }
        val second = StubParser(identity(parserId = "parser-two")) { _, _ ->
            ParseResult.Parsed(candidate())
        }
        val fixture = Fixture(
            parserRegistry = ParserRegistry(listOf(first, second)),
        )

        val result = fixture.service.ingest(fixture.rawEvent)

        val failed = assertFailed(result)
        assertEquals(IngestionFailure.AMBIGUOUS_PARSER, failed.failure)
        assertEquals(DiagnosticCode.AMBIGUOUS_PARSER, failed.diagnostic.code)
        assertTrue(fixture.evidenceReader.limits.isEmpty())
        assertTrue(fixture.commitStore.commits.isEmpty())
    }

    @Test
    fun `raw event append dispositions are preserved in recorded results`() = runTest {
        val alreadyPresent = Fixture(
            rawEventRepository = StubRawEventRepository(RawEventAppendResult.AlreadyPresent),
        )
        val duplicateObservation = Fixture(
            rawEventRepository = StubRawEventRepository(
                RawEventAppendResult.DuplicateObservation(existingObservationCount = 2),
            ),
        )

        val alreadyResult = assertRecorded(alreadyPresent.service.ingest(alreadyPresent.rawEvent))
        val duplicateResult = assertRecorded(
            duplicateObservation.service.ingest(duplicateObservation.rawEvent),
        )

        assertEquals(RawEventDisposition.ALREADY_PRESENT, alreadyResult.rawEventDisposition)
        assertEquals(
            RawEventDisposition.DUPLICATE_OBSERVATION,
            duplicateResult.rawEventDisposition,
        )
    }

    @Test
    fun `same parse key is idempotent while parser and rule versions append new attempts`() = runTest {
        val evidenceBytes = "evidence".toByteArray()
        val rawEvent = rawEvent(evidenceBytes = evidenceBytes)
        val repository = StubRawEventRepository(
            RawEventAppendResult.Inserted,
            RawEventAppendResult.AlreadyPresent,
            RawEventAppendResult.AlreadyPresent,
            RawEventAppendResult.AlreadyPresent,
        )
        val evidenceReader = StubEvidenceReader {
            EvidenceReadResult.Found(EvidenceInput("text/plain", evidenceBytes))
        }
        val commitStore = IdempotentCommitStore()

        fun service(identity: SourceIdentity, clockInstant: String): SourceIngestionService {
            val parser = StubParser(identity) { _, _ ->
                rejected(DiagnosticCode.INSUFFICIENT_FIELDS, recoverable = true)
            }
            return SourceIngestionService(
                rawEventRepository = repository,
                evidenceReader = evidenceReader,
                parserRegistry = ParserRegistry(listOf(parser)),
                commitStore = commitStore,
                clock = fixedClock(clockInstant),
                maxEvidenceBytes = MAX_EVIDENCE_BYTES,
            )
        }

        val first = assertRecorded(
            service(identity(), "2026-07-19T12:01:00Z").ingest(rawEvent),
        )
        val retry = assertRecorded(
            service(identity(), "2026-07-20T12:01:00Z").ingest(rawEvent),
        )
        val parserUpgrade = assertRecorded(
            service(
                identity(parserVersion = "parser-2"),
                "2026-07-21T12:01:00Z",
            ).ingest(rawEvent),
        )
        val rulesUpgrade = assertRecorded(
            service(
                identity(ruleVersion = "rules-2"),
                "2026-07-22T12:01:00Z",
            ).ingest(rawEvent),
        )

        assertEquals(first.attemptId, retry.attemptId)
        assertEquals(ParseCommitDisposition.COMMITTED, first.commitDisposition)
        assertEquals(ParseCommitDisposition.ALREADY_COMMITTED, retry.commitDisposition)
        assertNotEquals(first.attemptId, parserUpgrade.attemptId)
        assertNotEquals(first.attemptId, rulesUpgrade.attemptId)
        assertNotEquals(parserUpgrade.attemptId, rulesUpgrade.attemptId)
        assertEquals(ParseCommitDisposition.COMMITTED, parserUpgrade.commitDisposition)
        assertEquals(ParseCommitDisposition.COMMITTED, rulesUpgrade.commitDisposition)
        assertEquals(3, commitStore.persisted.size)
    }

    @Test
    fun `attempt id changes when any required parse key component changes`() = runTest {
        suspend fun captureId(
            event: RawEvent = rawEvent(),
            identity: SourceIdentity = identity(),
        ): ParseAttemptId {
            val fixture = Fixture(
                rawEvent = event,
                parser = StubParser(identity) { _, _ ->
                    rejected(DiagnosticCode.INSUFFICIENT_FIELDS, recoverable = true)
                },
            )
            return assertRecorded(fixture.service.ingest(event)).attemptId
        }

        val baseEvent = rawEvent()
        val ids = setOf(
            captureId(baseEvent, identity()),
            captureId(baseEvent.copy(id = RawEventId("event-2")), identity()),
            captureId(baseEvent, identity(parserId = "test-parser-2")),
            captureId(baseEvent, identity(parserVersion = "parser-2")),
            captureId(baseEvent, identity(ruleVersion = "rules-2")),
        )

        assertEquals(5, ids.size)
    }

    @Test
    fun `commit key collision is reported without claiming the attempt was recorded`() = runTest {
        val commitStore = StubCommitStore(result = ParseCommitResult.KeyCollision)
        val fixture = Fixture(commitStore = commitStore)

        val result = fixture.service.ingest(fixture.rawEvent)

        val failed = assertFailed(result)
        assertEquals(IngestionFailure.COMMIT_KEY_COLLISION, failed.failure)
        assertEquals(DiagnosticCode.COMMIT_CONFLICT, failed.diagnostic.code)
        assertNotNull(failed.attemptId)
        assertEquals(commitStore.commits.single().attempt.id, failed.attemptId)
    }

    @Test
    fun `evidence size budget must be positive`() {
        val fixture = Fixture()

        try {
            SourceIngestionService(
                rawEventRepository = fixture.rawEventRepository,
                evidenceReader = fixture.evidenceReader,
                parserRegistry = ParserRegistry(listOf(fixture.parser)),
                commitStore = fixture.commitStore,
                clock = fixedClock(),
                maxEvidenceBytes = 0,
            )
            fail("Expected a non-positive evidence budget to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected.message.orEmpty().contains("evidence".repeat(10)))
        }
    }

    private class Fixture(
        evidenceBytes: ByteArray = "evidence".toByteArray(),
        val rawEvent: RawEvent = rawEvent(evidenceBytes = evidenceBytes),
        val rawEventRepository: StubRawEventRepository = StubRawEventRepository(
            RawEventAppendResult.Inserted,
        ),
        evidenceResult: () -> EvidenceReadResult = {
            EvidenceReadResult.Found(EvidenceInput("text/plain", evidenceBytes))
        },
        val evidenceReader: StubEvidenceReader = StubEvidenceReader(evidenceResult),
        parseResult: ParseResult = rejected(
            DiagnosticCode.INSUFFICIENT_FIELDS,
            recoverable = true,
        ),
        parserHandler: ((RawEvent, EvidenceInput) -> ParseResult)? = null,
        val parser: StubParser = StubParser(identity()) { event, input ->
            parserHandler?.invoke(event, input) ?: parseResult
        },
        parserRegistry: ParserRegistry = ParserRegistry(listOf(parser)),
        val commitStore: StubCommitStore = StubCommitStore(),
    ) {
        val service = SourceIngestionService(
            rawEventRepository = rawEventRepository,
            evidenceReader = evidenceReader,
            parserRegistry = parserRegistry,
            commitStore = commitStore,
            clock = fixedClock(),
            maxEvidenceBytes = MAX_EVIDENCE_BYTES,
        )
    }

    private class StubRawEventRepository(
        vararg appendResults: RawEventAppendResult,
    ) : RawEventRepository {
        private val results = ArrayDeque(appendResults.toList())

        override suspend fun append(event: RawEvent): RawEventAppendResult =
            if (results.isEmpty()) {
                RawEventAppendResult.AlreadyPresent
            } else {
                results.removeFirst()
            }

        override suspend fun findById(id: RawEventId): RawEvent? = null
    }

    private class StubEvidenceReader(
        private val result: () -> EvidenceReadResult,
    ) : EvidenceReader() {
        val limits = mutableListOf<Long>()

        override suspend fun readBounded(
            payloadId: PayloadId,
            maxBytes: Long,
        ): EvidenceReadResult {
            limits += maxBytes
            return result()
        }
    }

    private class StubParser(
        override val identity: SourceIdentity,
        private val handler: (RawEvent, EvidenceInput) -> ParseResult,
    ) : SourceParser {
        var parseCalls: Int = 0
            private set

        override fun parse(rawEvent: RawEvent, evidenceInput: EvidenceInput): ParseResult {
            parseCalls += 1
            return handler(rawEvent, evidenceInput)
        }
    }

    private data class CommitCapture(
        val attempt: ParseAttempt,
        val draft: DraftProposal?,
    )

    private class StubCommitStore(
        private val result: ParseCommitResult = ParseCommitResult.Committed,
    ) : ParseCommitStore {
        val commits = mutableListOf<CommitCapture>()

        override suspend fun commit(
            attempt: ParseAttempt,
            draftProposal: DraftProposal?,
        ): ParseCommitResult {
            commits += CommitCapture(attempt, draftProposal)
            return result
        }
    }

    private class IdempotentCommitStore : ParseCommitStore {
        val persisted = linkedMapOf<ParseAttemptId, CommitCapture>()

        override suspend fun commit(
            attempt: ParseAttempt,
            draftProposal: DraftProposal?,
        ): ParseCommitResult {
            if (attempt.id in persisted) {
                return ParseCommitResult.AlreadyCommitted
            }
            persisted[attempt.id] = CommitCapture(attempt, draftProposal)
            return ParseCommitResult.Committed
        }
    }

    private fun assertSafeRecordedRejection(
        fixture: Fixture,
        result: IngestionResult,
        expectedCode: DiagnosticCode,
        expectedRecoverable: Boolean,
    ) {
        val recorded = assertRecorded(result)
        assertEquals(ParseAttemptOutcome.REJECTED, recorded.outcome)
        assertEquals(expectedCode, recorded.diagnostic?.code)
        assertEquals(expectedRecoverable, recorded.diagnostic?.recoverable)
        assertNull(recorded.draftProposalState)

        val committed = fixture.commitStore.commits.single()
        assertEquals(ParseAttemptOutcome.REJECTED, committed.attempt.outcome)
        val rejectedResult = committed.attempt.result as ParseResult.Rejected
        assertEquals(expectedCode, rejectedResult.diagnostic.code)
        assertEquals(expectedRecoverable, rejectedResult.diagnostic.recoverable)
        assertNull(committed.draft)
    }

    private fun assertRecorded(result: IngestionResult): IngestionResult.Recorded {
        assertTrue("Expected Recorded but got $result", result is IngestionResult.Recorded)
        return result as IngestionResult.Recorded
    }

    private fun assertFailed(result: IngestionResult): IngestionResult.Failed {
        assertTrue("Expected Failed but got $result", result is IngestionResult.Failed)
        return result as IngestionResult.Failed
    }

    private companion object {
        const val MAX_EVIDENCE_BYTES = 32L

        fun fixedClock(
            instant: String = "2026-07-19T12:01:00Z",
        ): Clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)

        fun identity(
            parserId: String = "test-parser",
            parserVersion: String = "parser-1",
            ruleVersion: String = "rules-1",
        ): SourceIdentity = SourceIdentity(
            parserId = ParserId(parserId),
            providerId = ProviderId("test-provider"),
            sourceFamily = SourceFamily.GENERIC,
            connectorId = ConnectorId("test-connector"),
            capabilities = setOf(SourceCapability.AMOUNT),
            supportedCaptureMethods = setOf(CaptureMethod.SHARE_TEXT),
            parserVersion = VersionId(parserVersion),
            ruleVersion = VersionId(ruleVersion),
        )

        fun rawEvent(
            evidenceBytes: ByteArray = "evidence".toByteArray(),
        ): RawEvent = RawEvent(
            id = RawEventId("event-1"),
            sourceFamily = SourceFamily.GENERIC,
            connectorId = ConnectorId("test-connector"),
            captureMethod = CaptureMethod.SHARE_TEXT,
            captureScope = CaptureScopeId("local-user"),
            contentHash = EvidenceHash.fromBytes(evidenceBytes),
            capturedAt = Instant.parse("2026-07-19T12:00:00Z"),
            payloadId = PayloadId("payload-1"),
        )

        fun candidate(): NormalizedCandidate = NormalizedCandidate(
            amount = FieldCandidate(
                value = Money.cny(1_234),
                confidence = 0.9,
                evidenceLocator = EvidenceLocator.WholePayload,
            ),
        )

        fun rejected(
            code: DiagnosticCode,
            recoverable: Boolean,
        ): ParseResult.Rejected = ParseResult.Rejected(
            SafeDiagnostic(
                code = code,
                recoverable = recoverable,
            ),
        )
    }
}
