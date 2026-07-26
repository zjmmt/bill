package dev.bill.source.contract

interface RawEventRepository {
    suspend fun append(event: RawEvent): RawEventAppendResult

    suspend fun findById(id: RawEventId): RawEvent?
}

sealed interface RawEventAppendResult {
    data object Inserted : RawEventAppendResult

    data object AlreadyPresent : RawEventAppendResult

    data class DuplicateObservation(
        val existingObservationCount: Int,
    ) : RawEventAppendResult {
        init {
            require(existingObservationCount > 0) {
                "A duplicate observation must reference at least one existing event"
            }
        }
    }

    data object IdCollision : RawEventAppendResult
}

abstract class EvidenceReader {
    suspend fun read(payloadId: PayloadId, maxBytes: Long): EvidenceReadResult {
        require(maxBytes > 0) { "Evidence read limit must be positive" }
        return readBounded(payloadId, maxBytes)
    }

    protected abstract suspend fun readBounded(
        payloadId: PayloadId,
        maxBytes: Long,
    ): EvidenceReadResult
}

sealed interface EvidenceReadResult {
    data class Found(val input: EvidenceInput) : EvidenceReadResult

    data object NotFound : EvidenceReadResult

    data class Failed(val diagnostic: SafeDiagnostic) : EvidenceReadResult
}
