package dev.bill.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventAppendResult
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRawEventRepositoryTest {
    private lateinit var database: BillDatabase
    private lateinit var repository: RoomRawEventRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BillDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomRawEventRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun appendPreservesEveryObservationAndDistinguishesReplayFromCollision() = runBlocking {
        val first = event("fixture-event-1")

        assertEquals(RawEventAppendResult.Inserted, repository.append(first))
        assertEquals(RawEventAppendResult.AlreadyPresent, repository.append(first))
        assertEquals(
            first.payloadId.value,
            database.sourceEvidenceDao().findPayload(first.id.value)?.payloadId,
        )
        assertEquals(
            RawEventAppendResult.IdCollision,
            repository.append(first.copy(payloadId = PayloadId("different-payload"))),
        )
        assertEquals(
            RawEventAppendResult.IdCollision,
            repository.append(
                event("fixture-event-payload-collision")
                    .copy(payloadId = first.payloadId),
            ),
        )

        val second = event(
            "fixture-event-2",
            capturedAt = first.capturedAt.plusSeconds(1),
        )
        assertEquals(
            RawEventAppendResult.DuplicateObservation(existingObservationCount = 1),
            repository.append(second),
        )
        val third = event("fixture-event-3", capturedAt = first.capturedAt.plusSeconds(2))
        assertEquals(
            RawEventAppendResult.DuplicateObservation(existingObservationCount = 2),
            repository.append(third),
        )

        assertEquals(first, repository.findById(first.id))
        assertEquals(second, repository.findById(second.id))
        assertEquals(third, repository.findById(third.id))

        val newEvidence = event(
            id = "fixture-event-4",
            hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
        )
        assertEquals(RawEventAppendResult.Inserted, repository.append(newEvidence))
    }

    @Test
    fun concurrentSameIdAppendHasOneInsertAndOneSafeReplay() = runBlocking {
        val event = event("fixture-event-concurrent")

        val results = listOf(
            async(Dispatchers.IO) { repository.append(event) },
            async(Dispatchers.IO) { repository.append(event) },
        ).awaitAll()

        assertEquals(
            setOf(RawEventAppendResult.Inserted, RawEventAppendResult.AlreadyPresent),
            results.toSet(),
        )
    }

    @Test
    fun corruptStoredEventFailsClosedWithoutEchoingRawValue() = runBlocking {
        val entity = RawEventEntityMapper.toEntity(event("fixture-corrupt-event"))
        database.rawEventDao().insertIfNewId(
            entity.copy(sourceFamily = "PRIVATE_UNKNOWN_FAMILY"),
        )
        database.sourceEvidenceDao().insertPayload(
            SourceEvidencePayloadEntity(
                rawEventId = entity.id,
                payloadId = entity.payloadReference,
                payloadSizeBytes = null,
                state = "AVAILABLE",
                clearCommandId = null,
                clearReason = null,
                clearRequestedAtEpochMillis = null,
                clearedAtEpochMillis = null,
            ),
        )

        val failure = runCatching {
            repository.findById(RawEventId("fixture-corrupt-event"))
        }.exceptionOrNull()

        assertTrue(failure is LocalDataIntegrityException)
        assertEquals("Local ledger data failed validation: raw event", failure?.message)
        assertFalse(failure?.message.orEmpty().contains("PRIVATE_UNKNOWN_FAMILY"))
    }

    private fun event(
        id: String,
        hash: String = ValidHash,
        capturedAt: Instant = Instant.parse("2025-07-20T09:46:40Z"),
    ) = RawEvent(
        id = RawEventId(id),
        sourceFamily = SourceFamily.BANK,
        connectorId = ConnectorId("fixture-connector"),
        // This repository test exercises observation identity, not staged-evidence admission.
        // User-provided file, text, notification and OCR captures require a staging lease.
        captureMethod = CaptureMethod.MANUAL,
        captureScope = CaptureScopeId("FIXTURE_ONLY"),
        contentHash = EvidenceHash(hash),
        capturedAt = capturedAt,
        payloadId = PayloadId("payload-$id"),
    )

    private companion object {
        const val ValidHash =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
