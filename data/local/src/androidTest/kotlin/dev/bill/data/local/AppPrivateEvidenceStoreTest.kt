package dev.bill.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bill.source.contract.EvidenceReadResult
import dev.bill.source.contract.PayloadId
import dev.bill.source.review.EvidenceStageResult
import dev.bill.source.review.EvidenceDeleteResult
import dev.bill.source.review.EvidenceMeasureResult
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPrivateEvidenceStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = AppPrivateEvidenceStore(context)

    @Test
    fun stageReadReplayCollisionAndDeleteStayInsideNoBackupStorage() = runBlocking {
        val payloadId = PayloadId("evidence-store-roundtrip")
        store.delete(payloadId)
        val original = "private shared text".toByteArray()

        assertEquals(
            EvidenceStageResult.Stored,
            store.stage(payloadId, "text/plain", original, 1024),
        )
        assertEquals(
            EvidenceStageResult.AlreadyStored,
            store.stage(payloadId, "text/plain", original, 1024),
        )
        assertEquals(
            EvidenceStageResult.IdCollision,
            store.stage(payloadId, "text/plain", "changed".toByteArray(), 1024),
        )
        val found = store.read(payloadId, 1024) as EvidenceReadResult.Found
        assertEquals("text/plain", found.input.mediaType)
        assertTrue(found.input.copyBytes().contentEquals(original))
        assertEquals(
            EvidenceMeasureResult.Found(original.size.toLong()),
            store.measure(payloadId, 1024),
        )

        val target = File(context.noBackupFilesDir, "source-evidence/${payloadId.value}.blob")
        assertEquals(context.noBackupFilesDir.canonicalFile, target.parentFile?.parentFile?.canonicalFile)
        assertEquals(EvidenceDeleteResult.DeletedOrAbsent, store.delete(payloadId))
        assertFalse(target.exists())
        assertEquals(EvidenceReadResult.NotFound, store.read(payloadId, 1024))
        assertEquals(EvidenceMeasureResult.NotFound, store.measure(payloadId, 1024))
    }

    @Test
    fun oversizedAndCorruptEvidenceFailClosed() = runBlocking {
        val oversizedId = PayloadId("evidence-store-oversized")
        val corruptId = PayloadId("evidence-store-corrupt")
        store.delete(oversizedId)
        store.delete(corruptId)

        assertEquals(
            EvidenceStageResult.TooLarge,
            store.stage(oversizedId, "text/plain", ByteArray(9), 8),
        )
        assertEquals(
            EvidenceStageResult.Stored,
            store.stage(corruptId, "text/plain", "secret".toByteArray(), 1024),
        )
        val target = File(context.noBackupFilesDir, "source-evidence/${corruptId.value}.blob")
        RandomAccessFile(target, "rw").use { file -> file.setLength(3) }

        val result = store.read(corruptId, 1024)
        assertTrue(result is EvidenceReadResult.Failed)
        assertFalse(result.toString().contains("secret"))
        assertEquals(EvidenceMeasureResult.Failed, store.measure(corruptId, 1024))
        assertEquals(EvidenceDeleteResult.DeletedOrAbsent, store.delete(corruptId))
    }

    @Test
    fun boundedOrphanScanExcludesTrackedIdsAndDeleteRemovesAtomicArtifacts() = runBlocking {
        val trackedId = PayloadId("evidence-scan-tracked")
        val orphanId = PayloadId("evidence-scan-orphan")
        val partialId = PayloadId("evidence-scan-partial")
        listOf(trackedId, orphanId, partialId).forEach { store.delete(it) }

        try {
            assertEquals(
                EvidenceStageResult.Stored,
                store.stage(trackedId, "text/plain", "tracked".toByteArray(), 1024),
            )
            assertEquals(
                EvidenceStageResult.Stored,
                store.stage(orphanId, "text/plain", "orphan".toByteArray(), 1024),
            )
            val directory = File(context.noBackupFilesDir, "source-evidence")
            val tracked = File(directory, "${trackedId.value}.blob")
            val orphan = File(directory, "${orphanId.value}.blob")
            val partial = File(directory, "${partialId.value}.blob.new")
            assertTrue(tracked.setLastModified(1L))
            assertTrue(orphan.setLastModified(1L))
            partial.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(partial.setLastModified(1L))

            val scan = store.scanOrphanCandidates(
                olderThanOrEqualTo = Instant.ofEpochMilli(2L),
                excludedPayloadIds = setOf(trackedId),
                limit = 10,
                inspectionLimit = 20,
            )

            assertEquals(setOf(orphanId, partialId), scan.payloadIds.toSet())
            assertFalse(scan.truncated)
            assertEquals(EvidenceDeleteResult.DeletedOrAbsent, store.delete(partialId))
            assertFalse(partial.exists())
        } finally {
            listOf(trackedId, orphanId, partialId).forEach { store.delete(it) }
        }
    }
}
