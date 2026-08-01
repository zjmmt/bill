package dev.bill.app

import dev.bill.app.notification.NotificationCaptureCoordinator
import dev.bill.application.NotificationCaptureResult
import dev.bill.application.NotificationEvidenceIngestionService
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.EvidenceReadResult
import dev.bill.source.contract.EvidenceReader
import dev.bill.source.contract.NotificationField
import dev.bill.source.contract.NotificationObservationId
import dev.bill.source.contract.NotificationObservationRepository
import dev.bill.source.contract.NotificationObservationReservation
import dev.bill.source.contract.NotificationObservationReserveResult
import dev.bill.source.contract.NotificationObservationReserveStatus
import dev.bill.source.contract.NotificationObservationWriteResult
import dev.bill.source.contract.NotificationObservationWriteStatus
import dev.bill.source.contract.ObservedEconomicEvent
import dev.bill.source.contract.ObservedMoneyDirection
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventAppendResult
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.RawEventRepository
import dev.bill.source.contract.SourceFamily
import dev.bill.source.genericnotification.NotificationContent
import dev.bill.source.genericnotification.NotificationMetadata
import dev.bill.source.genericnotification.NotificationTemplateGate
import dev.bill.source.pipeline.DraftProposal
import dev.bill.source.pipeline.DraftProposalReviewState
import dev.bill.source.pipeline.ParseAttempt
import dev.bill.source.pipeline.ParseAttemptId
import dev.bill.source.pipeline.ParseCommitResult
import dev.bill.source.pipeline.ParseCommitStore
import dev.bill.source.pipeline.ParserRegistry
import dev.bill.source.pipeline.SourceIngestionService
import dev.bill.source.review.EvidenceStageResult
import dev.bill.source.review.EvidenceStagingStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionNotificationPipelineTest {
    @Test
    fun `all production routes traverse the shared notification pipeline into review`() = runBlocking {
        val catalog = ProductionNotificationRoutes.catalog
        val fixtures = fixtures()
        val enabledRouteIds = catalog.presentations().mapTo(linkedSetOf()) { it.routeId }
        assertEquals(enabledRouteIds, fixtures.mapTo(linkedSetOf()) { it.routeId })

        val rawEvents = InMemoryRawEvents()
        val evidenceStore = InMemoryEvidenceStore()
        val commits = InMemoryCommitStore()
        val observations = InMemoryObservations()
        val clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)
        val sourceIngestionService = SourceIngestionService(
            rawEventRepository = rawEvents,
            evidenceReader = evidenceStore,
            parserRegistry = ParserRegistry(ProductionNotificationRoutes.parsers),
            commitStore = commits,
            clock = clock,
            maxEvidenceBytes = NotificationEvidenceIngestionService.MAX_NOTIFICATION_EVIDENCE_BYTES,
        )
        val ingress = NotificationEvidenceIngestionService(
            rawEventRepository = rawEvents,
            evidenceStore = evidenceStore,
            sourceIngestionService = sourceIngestionService,
            routeCatalog = catalog,
            isRouteEnabled = enabledRouteIds::contains,
            clock = clock,
        )

        fixtures.forEachIndexed { index, fixture ->
            val coordinator = NotificationCaptureCoordinator(
                gate = NotificationTemplateGate(catalog, enabledRouteIds::contains),
                observationRepository = observations,
                clock = clock,
                commandIdFactory = { "production-replay-$index" },
                leaseIdFactory = { "production-replay-lease-$index" },
                hasDurableUpdateDedupe = true,
            )
            assertTrue(coordinator.acceptsMetadata(fixture.metadata))
            val prepared = checkNotNull(
                coordinator.prepare(
                    observationId = NotificationObservationId(
                        index.toString(16).padStart(64, '0'),
                    ),
                    metadata = fixture.metadata,
                    postedAtEpochMillis = fixture.postedAtEpochMillis,
                    content = fixture.content,
                ),
            )
            assertEquals(fixture.routeId, prepared.route.routeId)

            val result = ingress.ingest(
                commandId = prepared.commandId,
                route = prepared.route,
                envelope = prepared.envelope,
            )
            assertTrue(result is NotificationCaptureResult.ReadyForReview)
            assertEquals(
                NotificationObservationWriteStatus.APPLIED,
                coordinator.complete(prepared, captured = true),
            )
        }

        assertEquals(fixtures.size, rawEvents.events.size)
        assertEquals(fixtures.size, evidenceStore.payloads.size)
        assertEquals(fixtures.size, commits.commits.size)
        assertEquals(fixtures.size, observations.captured.size)
        fixtures.forEach { fixture ->
            val commit = commits.commits.values.single { recorded ->
                recorded.attempt.sourceIdentity.connectorId.value == fixture.routeId
            }
            val proposal = checkNotNull(commit.proposal)
            assertEquals(DraftProposalReviewState.WAITING_USER, proposal.reviewState)
            assertEquals(fixture.amountMinorUnits, proposal.candidate?.amount?.value?.minorUnits)
            assertEquals(fixture.direction, proposal.candidate?.moneyDirection?.value)
            assertEquals(fixture.economicEvent, proposal.candidate?.economicEvent?.value)
            assertNull(proposal.candidate?.counterparty)
            assertNull(proposal.candidate?.fundingHint)

            val rawEvent = rawEvents.events.values.single { event ->
                event.connectorId.value == fixture.routeId
            }
            assertEquals(fixture.sourceFamily, rawEvent.sourceFamily)
            assertEquals(CaptureMethod.NOTIFICATION, rawEvent.captureMethod)
        }
    }

    private fun fixtures(): List<RouteReplay> = listOf(
        replay(
            routeId = "alipay.notification.outbound-cny.v1",
            packageName = "com.eg.android.AlipayGphone",
            channelId = "alipay_default",
            category = null,
            title = "交易提醒",
            text = "你有一笔￥12.34的支出，请在支付宝内核对",
            amountMinorUnits = 1_234L,
            direction = ObservedMoneyDirection.OUTBOUND,
            sourceFamily = SourceFamily.ALIPAY,
        ),
        replay(
            routeId = "alipay.notification.balance-receipt-cny.v1",
            packageName = "com.eg.android.AlipayGphone",
            channelId = "alipay_default",
            category = null,
            title = "示例成功收款56.78元，获得消费金",
            text = "已转入余额，请在账单中核对",
            amountMinorUnits = 5_678L,
            direction = ObservedMoneyDirection.INBOUND,
            sourceFamily = SourceFamily.ALIPAY,
        ),
        replay(
            routeId = "alipay.notification.fund-buy-confirmed-cny.v1",
            packageName = "com.eg.android.AlipayGphone",
            channelId = "alipay_default",
            category = null,
            title = "基金申购确认成功通知",
            text = "确认金额：90.12元 手续费：0.00元",
            amountMinorUnits = 9_012L,
            direction = ObservedMoneyDirection.OUTBOUND,
            sourceFamily = SourceFamily.ALIPAY,
            economicEvent = ObservedEconomicEvent.INVEST_BUY,
        ),
        replay(
            routeId = "wechat.notification.paid-en-cny.v1",
            packageName = "com.tencent.mm",
            channelId = "message_channel_new_id",
            category = "msg",
            title = "微信支付",
            text = "¥34.56 paid",
            amountMinorUnits = 3_456L,
            direction = ObservedMoneyDirection.OUTBOUND,
            sourceFamily = SourceFamily.WECHAT,
        ),
        replay(
            routeId = "bank.cmb.notification.quick-refund-cny.v1",
            packageName = "cmb.pb",
            channelId = "channelId4oppoandroidp",
            category = null,
            title = "招商银行",
            text = "示例账户支付完成，现收到快捷支付退款人民币78.90元",
            amountMinorUnits = 7_890L,
            direction = ObservedMoneyDirection.INBOUND,
            sourceFamily = SourceFamily.BANK,
        ),
    )

    private fun replay(
        routeId: String,
        packageName: String,
        channelId: String,
        category: String?,
        title: String,
        text: String,
        amountMinorUnits: Long,
        direction: ObservedMoneyDirection,
        sourceFamily: SourceFamily,
        economicEvent: ObservedEconomicEvent? = null,
    ) = RouteReplay(
        routeId = routeId,
        metadata = NotificationMetadata(packageName, channelId, category),
        postedAtEpochMillis = 1_800_000_000_000L + amountMinorUnits,
        content = checkNotNull(
            NotificationContent.from(
                mapOf(
                    NotificationField.TITLE to title,
                    NotificationField.TEXT to text,
                ),
            ),
        ),
        amountMinorUnits = amountMinorUnits,
        direction = direction,
        sourceFamily = sourceFamily,
        economicEvent = economicEvent,
    )

    private data class RouteReplay(
        val routeId: String,
        val metadata: NotificationMetadata,
        val postedAtEpochMillis: Long,
        val content: NotificationContent,
        val amountMinorUnits: Long,
        val direction: ObservedMoneyDirection,
        val sourceFamily: SourceFamily,
        val economicEvent: ObservedEconomicEvent?,
    )

    private class InMemoryRawEvents : RawEventRepository {
        val events = linkedMapOf<RawEventId, RawEvent>()

        override suspend fun append(event: RawEvent): RawEventAppendResult {
            val existing = events[event.id]
            return when {
                existing == null -> {
                    events[event.id] = event
                    RawEventAppendResult.Inserted
                }

                existing == event -> RawEventAppendResult.AlreadyPresent
                else -> RawEventAppendResult.IdCollision
            }
        }

        override suspend fun findById(id: RawEventId): RawEvent? = events[id]
    }

    private class InMemoryEvidenceStore : EvidenceReader(), EvidenceStagingStore {
        data class Payload(val mediaType: String, val bytes: ByteArray)

        val payloads = linkedMapOf<PayloadId, Payload>()

        override suspend fun stage(
            payloadId: PayloadId,
            mediaType: String,
            bytes: ByteArray,
            maxBytes: Long,
        ): EvidenceStageResult {
            if (bytes.size.toLong() > maxBytes) return EvidenceStageResult.TooLarge
            val existing = payloads[payloadId]
            return when {
                existing == null -> {
                    payloads[payloadId] = Payload(mediaType, bytes.copyOf())
                    EvidenceStageResult.Stored
                }

                existing.mediaType == mediaType && existing.bytes.contentEquals(bytes) ->
                    EvidenceStageResult.AlreadyStored

                else -> EvidenceStageResult.IdCollision
            }
        }

        override suspend fun readBounded(
            payloadId: PayloadId,
            maxBytes: Long,
        ): EvidenceReadResult {
            val payload = payloads[payloadId] ?: return EvidenceReadResult.NotFound
            if (payload.bytes.size.toLong() > maxBytes) return EvidenceReadResult.NotFound
            return EvidenceReadResult.Found(EvidenceInput(payload.mediaType, payload.bytes))
        }
    }

    private class InMemoryCommitStore : ParseCommitStore {
        data class Recorded(val attempt: ParseAttempt, val proposal: DraftProposal?)

        val commits = linkedMapOf<ParseAttemptId, Recorded>()

        override suspend fun commit(
            attempt: ParseAttempt,
            draftProposal: DraftProposal?,
        ): ParseCommitResult {
            val incoming = Recorded(attempt, draftProposal)
            val existing = commits[attempt.id]
            if (existing != null) {
                return if (existing == incoming) {
                    ParseCommitResult.AlreadyCommitted
                } else {
                    ParseCommitResult.KeyCollision
                }
            }
            commits[attempt.id] = incoming
            return ParseCommitResult.Committed
        }
    }

    private class InMemoryObservations : NotificationObservationRepository {
        private val reservations = linkedMapOf<NotificationObservationId, NotificationObservationReservation>()
        val captured = linkedSetOf<NotificationObservationId>()

        override suspend fun reserve(
            requested: NotificationObservationReservation,
            now: Instant,
        ): NotificationObservationReserveResult {
            if (requested.observationId in reservations) {
                return NotificationObservationReserveResult(
                    NotificationObservationReserveStatus.IN_PROGRESS,
                )
            }
            reservations[requested.observationId] = requested
            return NotificationObservationReserveResult(
                status = NotificationObservationReserveStatus.RESERVED,
                reservation = requested,
            )
        }

        override suspend fun markCaptured(
            reservation: NotificationObservationReservation,
            capturedAt: Instant,
        ): NotificationObservationWriteResult {
            if (reservations[reservation.observationId] != reservation) {
                return NotificationObservationWriteResult(
                    NotificationObservationWriteStatus.INVALID_STATE,
                )
            }
            captured += reservation.observationId
            return NotificationObservationWriteResult(NotificationObservationWriteStatus.APPLIED)
        }

        override suspend fun release(
            reservation: NotificationObservationReservation,
        ): NotificationObservationWriteResult {
            val removed = reservations.remove(reservation.observationId) == reservation
            return NotificationObservationWriteResult(
                if (removed) {
                    NotificationObservationWriteStatus.APPLIED
                } else {
                    NotificationObservationWriteStatus.INVALID_STATE
                },
            )
        }
    }
}
