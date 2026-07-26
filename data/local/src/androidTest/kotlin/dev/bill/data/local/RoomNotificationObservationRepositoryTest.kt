package dev.bill.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bill.source.contract.NotificationCaptureCommandId
import dev.bill.source.contract.NotificationObservationId
import dev.bill.source.contract.NotificationObservationLeaseId
import dev.bill.source.contract.NotificationObservationReservation
import dev.bill.source.contract.NotificationObservationReserveStatus
import dev.bill.source.contract.NotificationObservationWriteStatus
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomNotificationObservationRepositoryTest {
    private lateinit var database: BillDatabase
    private lateinit var repository: RoomNotificationObservationRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BillDatabase::class.java,
        )
            .addCallback(BillDatabaseCallbacks.EnsureSourceEvidencePolicy)
            .allowMainThreadQueries()
            .build()
        repository = RoomNotificationObservationRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun expiredLeaseReusesOriginalCommandAndCapturedObservationSuppressesUpdates() = runBlocking {
        val first = reservation(commandId = "first-command", leaseId = "first-lease")
        assertEquals(
            NotificationObservationReserveStatus.RESERVED,
            repository.reserve(first, BaseTime).status,
        )

        val competing = reservation(
            commandId = "different-command",
            leaseId = "competing-lease",
            createdAt = BaseTime.plusSeconds(1),
        )
        assertEquals(
            NotificationObservationReserveStatus.IN_PROGRESS,
            repository.reserve(competing, competing.createdAt).status,
        )

        val resumeAt = first.expiresAt.plusSeconds(1)
        val resumedRequest = reservation(
            commandId = "would-be-new-command",
            leaseId = "resumed-lease",
            createdAt = resumeAt,
        )
        val resumed = repository.reserve(resumedRequest, resumeAt)
        assertEquals(NotificationObservationReserveStatus.RESUMED, resumed.status)
        assertEquals("first-command", resumed.reservation?.commandId?.value)
        assertEquals("resumed-lease", resumed.reservation?.leaseId?.value)

        assertEquals(
            NotificationObservationWriteStatus.APPLIED,
            repository.markCaptured(requireNotNull(resumed.reservation), resumeAt).status,
        )
        assertEquals(
            NotificationObservationReserveStatus.ALREADY_CAPTURED,
            repository.reserve(resumedRequest, resumeAt.plusSeconds(1)).status,
        )
    }

    @Test
    fun releasedLeaseAllowsASafeFreshCommand() = runBlocking {
        val first = reservation(commandId = "release-first", leaseId = "release-lease")
        repository.reserve(first, BaseTime)
        assertEquals(
            NotificationObservationWriteStatus.APPLIED,
            repository.release(first).status,
        )

        val second = reservation(
            commandId = "release-second",
            leaseId = "release-second-lease",
            createdAt = BaseTime.plusSeconds(1),
        )
        val reserved = repository.reserve(second, second.createdAt)
        assertEquals(NotificationObservationReserveStatus.RESERVED, reserved.status)
        assertEquals("release-second", reserved.reservation?.commandId?.value)
    }

    @Test
    fun staleActiveObservationIsPrunedByALaterCandidateWithoutBackgroundWork() = runBlocking {
        val stale = reservation(
            commandId = "stale-command",
            leaseId = "stale-lease",
            observationId = "b".repeat(64),
        )
        assertEquals(
            NotificationObservationReserveStatus.RESERVED,
            repository.reserve(stale, BaseTime).status,
        )

        val maintenanceAt = BaseTime.plus(ObservationRetention).plusSeconds(121)
        val trigger = reservation(
            commandId = "maintenance-command",
            leaseId = "maintenance-lease",
            observationId = "c".repeat(64),
            createdAt = maintenanceAt,
        )
        assertEquals(
            NotificationObservationReserveStatus.RESERVED,
            repository.reserve(trigger, maintenanceAt).status,
        )
        assertNull(database.notificationObservationDao().findByObservationId(stale.observationId.value))

        val freshForFormerId = reservation(
            commandId = "fresh-command",
            leaseId = "fresh-lease",
            observationId = stale.observationId.value,
            createdAt = maintenanceAt.plusSeconds(1),
        )
        assertEquals(
            NotificationObservationReserveStatus.RESERVED,
            repository.reserve(freshForFormerId, freshForFormerId.createdAt).status,
        )
    }

    @Test
    fun processReopenResumesTheSameCommandThenPersistsCapturedSuppression() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "notification-observation-restart-test.db"
        context.deleteDatabase(databaseName)
        var initial: BillDatabase? = null
        var resumed: BillDatabase? = null
        var verified: BillDatabase? = null
        try {
            val first = reservation(
                commandId = "restart-original-command",
                leaseId = "restart-original-lease",
            )
            initial = diskDatabase(context, databaseName)
            assertEquals(
                NotificationObservationReserveStatus.RESERVED,
                RoomNotificationObservationRepository(requireNotNull(initial))
                    .reserve(first, BaseTime)
                    .status,
            )
            requireNotNull(initial).close()
            initial = null

            val resumeAt = first.expiresAt.plusSeconds(1)
            val restartRequest = reservation(
                commandId = "restart-new-command",
                leaseId = "restart-new-lease",
                createdAt = resumeAt,
            )
            resumed = diskDatabase(context, databaseName)
            val resumedRepository = RoomNotificationObservationRepository(requireNotNull(resumed))
            val recovered = resumedRepository.reserve(restartRequest, resumeAt)
            assertEquals(NotificationObservationReserveStatus.RESUMED, recovered.status)
            assertEquals("restart-original-command", recovered.reservation?.commandId?.value)
            assertEquals(
                NotificationObservationWriteStatus.APPLIED,
                resumedRepository.markCaptured(requireNotNull(recovered.reservation), resumeAt).status,
            )
            requireNotNull(resumed).close()
            resumed = null

            verified = diskDatabase(context, databaseName)
            assertEquals(
                NotificationObservationReserveStatus.ALREADY_CAPTURED,
                RoomNotificationObservationRepository(requireNotNull(verified)).reserve(
                    reservation(
                        commandId = "restart-third-command",
                        leaseId = "restart-third-lease",
                        createdAt = resumeAt.plusSeconds(1),
                    ),
                    resumeAt.plusSeconds(1),
                ).status,
            )
        } finally {
            initial?.close()
            resumed?.close()
            verified?.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun diskDatabase(
        context: android.content.Context,
        databaseName: String,
    ): BillDatabase = Room.databaseBuilder(
        context,
        BillDatabase::class.java,
        databaseName,
    )
        .addCallback(BillDatabaseCallbacks.EnsureSourceEvidencePolicy)
        .allowMainThreadQueries()
        .build()

    private fun reservation(
        commandId: String,
        leaseId: String,
        observationId: String = "a".repeat(64),
        createdAt: Instant = BaseTime,
    ) = NotificationObservationReservation(
        observationId = NotificationObservationId(observationId),
        commandId = NotificationCaptureCommandId(commandId),
        leaseId = NotificationObservationLeaseId(leaseId),
        createdAt = createdAt,
        expiresAt = createdAt.plusSeconds(120),
    )

    private companion object {
        val BaseTime: Instant = Instant.parse("2026-07-26T08:00:00Z")
        val ObservationRetention: Duration = Duration.ofDays(90)
    }
}
