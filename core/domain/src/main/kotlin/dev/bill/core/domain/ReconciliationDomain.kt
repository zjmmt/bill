package dev.bill.core.domain

import dev.bill.core.model.TransactionId
import dev.bill.core.model.TransactionType
import java.time.Instant

@JvmInline
value class TransactionRelationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Transaction relation id cannot be blank" }
        require(value.length <= 160) { "Transaction relation id is too long" }
    }
}

enum class ReconciliationKind {
    TRANSFER_PAIR,
    LIABILITY_REPAYMENT,
    REFUND,
}

enum class ReconciliationDraftRole {
    TRANSFER_OUTBOUND,
    TRANSFER_INBOUND,
    REPAYMENT_OUTBOUND,
    REFUND_INBOUND,
}

data class ReconciliationDraftLink(
    val draftId: DraftId,
    val role: ReconciliationDraftRole,
)

enum class TransactionRelationType {
    REFUNDS,
}

enum class RelationDecision {
    USER_CONFIRMED,
}

data class TransactionRelation(
    val id: TransactionRelationId,
    /** Directional relation source; for REFUNDS this is the refund transaction. */
    val fromTransactionId: TransactionId,
    /** Directional relation target; for REFUNDS this is the original expense. */
    val toTransactionId: TransactionId,
    val type: TransactionRelationType,
    val decision: RelationDecision,
    val createdAt: Instant,
) {
    init {
        require(fromTransactionId != toTransactionId) {
            "A transaction cannot relate to itself"
        }
    }
}

/**
 * One user-confirmed replacement posting and every Draft/evidence chain it absorbs.
 *
 * The replacement transaction intentionally has no single [PostedTransaction.draftId]; the
 * explicit links below support one or two source Drafts without discarding either provenance.
 */
data class ReconciliationResolution(
    val kind: ReconciliationKind,
    val transaction: PostedTransaction,
    val draftLinks: List<ReconciliationDraftLink>,
    val relations: List<TransactionRelation>,
) {
    init {
        require(transaction.draftId == null)
        require(transaction.status == TransactionStatus.ACTIVE)
        require(draftLinks.isNotEmpty())
        require(draftLinks.map(ReconciliationDraftLink::draftId).distinct().size == draftLinks.size)
        require(relations.all { it.fromTransactionId == transaction.id })

        when (kind) {
            ReconciliationKind.TRANSFER_PAIR -> {
                require(transaction.type == TransactionType.TRANSFER)
                require(draftLinks.size == 2)
                require(
                    draftLinks.mapTo(mutableSetOf(), ReconciliationDraftLink::role) ==
                        setOf(
                            ReconciliationDraftRole.TRANSFER_OUTBOUND,
                            ReconciliationDraftRole.TRANSFER_INBOUND,
                        ),
                )
                require(relations.isEmpty())
            }

            ReconciliationKind.LIABILITY_REPAYMENT -> {
                require(transaction.type == TransactionType.LIABILITY_REPAY)
                require(
                    draftLinks.singleOrNull()?.role ==
                        ReconciliationDraftRole.REPAYMENT_OUTBOUND,
                )
                require(relations.isEmpty())
            }

            ReconciliationKind.REFUND -> {
                require(transaction.type == TransactionType.REFUND)
                require(
                    draftLinks.singleOrNull()?.role ==
                        ReconciliationDraftRole.REFUND_INBOUND,
                )
                require(
                    relations.singleOrNull()?.type == TransactionRelationType.REFUNDS,
                )
            }
        }
    }
}
