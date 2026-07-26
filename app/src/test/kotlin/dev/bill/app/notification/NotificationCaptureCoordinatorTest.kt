package dev.bill.app.notification

import dev.bill.source.contract.NotificationCaptureCommandId
import dev.bill.source.contract.NotificationField
import dev.bill.source.contract.NotificationObservationId
import dev.bill.source.contract.NotificationObservationLeaseId
import dev.bill.source.contract.NotificationObservationRepository
import dev.bill.source.contract.NotificationObservationReservation
import dev.bill.source.contract.NotificationObservationReserveResult
import dev.bill.source.contract.NotificationObservationReserveStatus
import dev.bill.source.contract.NotificationObservationWriteResult
import dev.bill.source.contract.NotificationObservationWriteStatus
import dev.bill.source.genericnotification.NotificationContent
import dev.bill.source.genericnotification.NotificationMetadata
import dev.bill.source.genericnotification.NotificationTemplate
import dev.bill.source.genericnotification.NotificationTemplateGate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCaptureCoordinatorTest {
    private val metadata = NotificationMetadata(
        packageName = "fixture.payment",
        channelId = "transaction",
        category = "status",
    )
    private val observationId = NotificationObservationId("b".repeat(64))

    @Test
    fun `empty production catalog is never eligible for notification extras`() {
        val coordinator = NotificationCaptureCoordinator(NotificationTemplateGate(emptyList()))

        assertFalse(coordinator.acceptsMetadata(metadata))
    }

    @Test
    fun `template remains inert until the durable runtime release gate opens`() {
        val coordinator = NotificationCaptureCoordinator(
            gate = NotificationTemplateGate(listOf(fixtureTemplate())),
        )

        assertFalse(coordinator.acceptsMetadata(metadata))
        val prepared = runBlocking {
            coordinator.prepare(
                observationId = observationId,
                metadata = metadata,
                postedAtEpochMillis = 1_700_000_000_000L,
                content = content("private paid marker"),
            )
        }
        assertNull(prepared)
    }

    @Test
    fun `verified content reserves a redacted durable capture handoff`() = runBlocking {
        val secret = "private paid marker"
        val observations = FakeObservations()
        val coordinator = NotificationCaptureCoordinator(
            gate = NotificationTemplateGate(listOf(fixtureTemplate(secret))),
            observationRepository = observations,
            clock = fixedClock(),
            commandIdFactory = { "synthetic-command" },
            leaseIdFactory = { "synthetic-lease" },
            hasDurableUpdateDedupe = true,
        )

        assertTrue(coordinator.acceptsMetadata(metadata))
        val prepared = coordinator.prepare(
            observationId = observationId,
            metadata = metadata,
            postedAtEpochMillis = 1_700_000_000_000L,
            content = content(secret),
        )

        assertTrue(prepared != null)
        prepared ?: return@runBlocking
        assertEquals("synthetic-command", prepared.commandId)
        assertEquals("fixture-payment", prepared.envelope.templateId)
        assertEquals("v1", prepared.envelope.templateVersion)
        assertEquals(secret, prepared.envelope.content.field(NotificationField.TEXT))
        assertFalse(prepared.toString().contains(secret))
        assertEquals("synthetic-command", observations.lastReservation?.commandId?.value)

        coordinator.complete(prepared, captured = true)
        assertEquals("synthetic-command", observations.capturedCommandId)
    }

    @Test
    fun `in progress or captured durable observation never creates a second handoff`() = runBlocking {
        val observations = FakeObservations(
            reserveStatus = NotificationObservationReserveStatus.IN_PROGRESS,
        )
        val coordinator = NotificationCaptureCoordinator(
            gate = NotificationTemplateGate(listOf(fixtureTemplate())),
            observationRepository = observations,
            clock = fixedClock(),
            commandIdFactory = { "dedupe-command" },
            leaseIdFactory = { "dedupe-lease" },
            hasDurableUpdateDedupe = true,
        )

        assertNull(
            coordinator.prepare(
                observationId = observationId,
                metadata = metadata,
                postedAtEpochMillis = 1_700_000_000_000L,
                content = content("private paid marker"),
            ),
        )
        assertEquals(1, observations.reserveCalls)
    }

    @Test
    fun `invalid command identifier fails without reserving an observation`() = runBlocking {
        val observations = FakeObservations()
        val coordinator = NotificationCaptureCoordinator(
            gate = NotificationTemplateGate(listOf(fixtureTemplate())),
            observationRepository = observations,
            commandIdFactory = { "invalid command id" },
            hasDurableUpdateDedupe = true,
        )

        assertNull(
            coordinator.prepare(
                observationId = observationId,
                metadata = metadata,
                postedAtEpochMillis = 1_700_000_000_000L,
                content = content("private paid marker"),
            ),
        )
        assertEquals(0, observations.reserveCalls)
    }

    private fun content(text: String): NotificationContent =
        checkNotNull(NotificationContent.from(mapOf(NotificationField.TEXT to text)))

    private fun fixtureTemplate(marker: String = "private paid marker") = NotificationTemplate(
        id = "fixture-payment",
        version = "v1",
        packageName = "fixture.payment",
        channelId = "transaction",
        category = "status",
        contentMatcher = { it.field(NotificationField.TEXT) == marker },
    )

    private fun fixedClock(): Clock = Clock.fixed(
        Instant.parse("2026-07-26T08:00:00Z"),
        ZoneOffset.UTC,
    )

    private class FakeObservations(
        private val reserveStatus: NotificationObservationReserveStatus =
            NotificationObservationReserveStatus.RESERVED,
    ) : NotificationObservationRepository {
        var reserveCalls: Int = 0
        var lastReservation: NotificationObservationReservation? = null
        var capturedCommandId: String? = null

        override suspend fun reserve(
            requested: NotificationObservationReservation,
            now: Instant,
        ): NotificationObservationReserveResult {
            reserveCalls += 1
            lastReservation = requested
            return NotificationObservationReserveResult(
                status = reserveStatus,
                reservation = requested.takeIf {
                    reserveStatus == NotificationObservationReserveStatus.RESERVED ||
                        reserveStatus == NotificationObservationReserveStatus.RESUMED
                },
            )
        }

        override suspend fun markCaptured(
            reservation: NotificationObservationReservation,
            capturedAt: Instant,
        ): NotificationObservationWriteResult {
            capturedCommandId = reservation.commandId.value
            return NotificationObservationWriteResult(NotificationObservationWriteStatus.APPLIED)
        }

        override suspend fun release(
            reservation: NotificationObservationReservation,
        ): NotificationObservationWriteResult = NotificationObservationWriteResult(
            NotificationObservationWriteStatus.APPLIED,
        )
    }
}
