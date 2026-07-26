package dev.bill.source.review

import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.RepositoryWriteResult
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.core.domain.ReviewDraft
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.isSupportedLedgerCurrency
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.NormalizedCandidate
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.SafeDiagnostic
import dev.bill.source.contract.SourceFamily
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class SourceProposalRecord(
    val id: String,
    val rawEventId: String,
    val parseAttemptId: String,
    /** Opaque parser provenance only; never Android package/channel/category or body text. */
    val parserId: String = "generic-review",
    val providerId: String = "generic-review",
    val connectorId: String = "generic-review",
    val sourceFamily: SourceFamily,
    val captureMethod: CaptureMethod,
    val capturedAt: Instant,
    val diagnostic: SafeDiagnostic?,
    val candidate: NormalizedCandidate?,
    val isPossibleDuplicate: Boolean,
)

/**
 * This is a source-evidence boundary, not an account capability. A USD bank account is valid,
 * but a wallet/unknown source must never create a USD external draft merely because a user typed
 * a dollar amount into the review form.
 */
fun SourceProposalRecord.allowedExternalDraftCurrencies(): Set<CurrencyCode> = when (sourceFamily) {
    SourceFamily.ALIPAY,
    SourceFamily.WECHAT,
    SourceFamily.GENERIC,
    SourceFamily.MANUAL,
    -> setOf(CurrencyCode.CNY)

    SourceFamily.BANK -> candidate?.amount?.value?.currency
        ?.takeIf(CurrencyCode::isSupportedLedgerCurrency)
        ?.let(::setOf)
        .orEmpty()
}

fun SourceProposalRecord.allowsExternalDraftCurrency(currency: CurrencyCode): Boolean =
    currency in allowedExternalDraftCurrencies()

interface SourceReviewRepository {
    fun observePendingSourceProposals(): Flow<List<SourceProposalRecord>>

    suspend fun completeSourceProposal(
        proposalId: String,
        draft: ReviewDraft,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult

    suspend fun dismissSourceProposal(
        proposalId: String,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult

    data object Empty : SourceReviewRepository {
        override fun observePendingSourceProposals(): Flow<List<SourceProposalRecord>> =
            flowOf(emptyList())

        override suspend fun completeSourceProposal(
            proposalId: String,
            draft: ReviewDraft,
            auditRecord: AuditRecord,
        ): RepositoryWriteResult = RepositoryWriteResult(
            status = RepositoryWriteStatus.NOT_FOUND,
        )

        override suspend fun dismissSourceProposal(
            proposalId: String,
            auditRecord: AuditRecord,
        ): RepositoryWriteResult = RepositoryWriteResult(
            status = RepositoryWriteStatus.NOT_FOUND,
        )
    }
}

sealed interface EvidenceStageResult {
    data object Stored : EvidenceStageResult
    data object AlreadyStored : EvidenceStageResult
    data object IdCollision : EvidenceStageResult
    data object TooLarge : EvidenceStageResult
    data object Failed : EvidenceStageResult
}

interface EvidenceStagingStore {
    suspend fun stage(
        payloadId: PayloadId,
        mediaType: String,
        bytes: ByteArray,
        maxBytes: Long,
    ): EvidenceStageResult
}
