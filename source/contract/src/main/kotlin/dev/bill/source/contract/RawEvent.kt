package dev.bill.source.contract

import java.time.Instant

enum class SourceFamily {
    ALIPAY,
    WECHAT,
    BANK,
    GENERIC,
    MANUAL,
}

enum class CaptureMethod {
    NOTIFICATION,
    STATEMENT_IMPORT,
    SHARE_TEXT,
    SHARE_FILE,
    PHOTO_OCR,
    MANUAL,
}

/** Immutable metadata for one captured piece of evidence. */
data class RawEvent(
    val id: RawEventId,
    val sourceFamily: SourceFamily,
    val connectorId: ConnectorId,
    val captureMethod: CaptureMethod,
    val captureScope: CaptureScopeId,
    val contentHash: EvidenceHash,
    val capturedAt: Instant,
    val payloadId: PayloadId,
    val payloadSizeBytes: Long? = null,
) {
    init {
        require(payloadSizeBytes == null || payloadSizeBytes >= 0L) {
            "Payload size cannot be negative"
        }
    }
}
