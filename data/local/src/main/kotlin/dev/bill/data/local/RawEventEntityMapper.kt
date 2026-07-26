package dev.bill.data.local

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import java.time.DateTimeException
import java.time.Instant

internal object RawEventEntityMapper {
    fun toEntity(event: RawEvent): RawEventEntity = try {
        RawEventEntity(
            id = event.id.value,
            sourceFamily = event.sourceFamily.name,
            connectorId = event.connectorId.value,
            captureMethod = event.captureMethod.name,
            captureScope = event.captureScope.value,
            contentHash = event.contentHash.value,
            capturedAtEpochMillis = event.capturedAt.toEpochMilli(),
            payloadReference = event.payloadId.value,
        )
    } catch (_: ArithmeticException) {
        throw integrityFailure()
    }

    fun toDomain(entity: RawEventEntity): RawEvent = try {
        RawEvent(
            id = RawEventId(entity.id),
            sourceFamily = enumValueOf<SourceFamily>(entity.sourceFamily),
            connectorId = ConnectorId(entity.connectorId),
            captureMethod = enumValueOf<CaptureMethod>(entity.captureMethod),
            captureScope = CaptureScopeId(entity.captureScope),
            contentHash = EvidenceHash(entity.contentHash),
            capturedAt = Instant.ofEpochMilli(entity.capturedAtEpochMillis),
            payloadId = PayloadId(entity.payloadReference),
        )
    } catch (_: IllegalArgumentException) {
        throw integrityFailure()
    } catch (_: DateTimeException) {
        throw integrityFailure()
    }

    private fun integrityFailure(): LocalDataIntegrityException =
        LocalDataIntegrityException("raw event")
}
