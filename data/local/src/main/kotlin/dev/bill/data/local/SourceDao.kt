package dev.bill.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM parse_attempts WHERE id = :id")
    suspend fun findParseAttempt(id: String): ParseAttemptEntity?

    @Query(
        """
        SELECT * FROM parse_attempts
        WHERE rawEventId = :rawEventId
          AND parserId = :parserId
          AND parserVersion = :parserVersion
          AND ruleVersion = :ruleVersion
        LIMIT 1
        """,
    )
    suspend fun findParseAttemptByKey(
        rawEventId: String,
        parserId: String,
        parserVersion: String,
        ruleVersion: String,
    ): ParseAttemptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParseAttempt(attempt: ParseAttemptEntity)

    @Query("SELECT * FROM source_draft_proposals WHERE id = :id")
    suspend fun findProposal(id: String): SourceDraftProposalEntity?

    @Query(
        "SELECT * FROM source_draft_proposals WHERE parseAttemptId = :parseAttemptId LIMIT 1",
    )
    suspend fun findProposalByAttemptId(parseAttemptId: String): SourceDraftProposalEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProposal(proposal: SourceDraftProposalEntity)

    @Query(
        """
        SELECT
            p.id AS proposalId,
            p.state AS proposalState,
            p.createdAtEpochMillis AS proposalCreatedAtEpochMillis,
            r.id AS rawEventId,
            r.sourceFamily AS sourceFamily,
            r.connectorId AS rawConnectorId,
            r.captureMethod AS captureMethod,
            r.capturedAtEpochMillis AS capturedAtEpochMillis,
            a.id AS parseAttemptId,
            a.rawEventId AS attemptRawEventId,
            a.parserId AS parserId,
            a.providerId AS providerId,
            a.sourceFamily AS attemptSourceFamily,
            a.connectorId AS connectorId,
            a.parserVersion AS parserVersion,
            a.ruleVersion AS ruleVersion,
            a.outcome AS parseOutcome,
            a.diagnosticCode AS diagnosticCode,
            a.diagnosticRecoverable AS diagnosticRecoverable,
            a.candidatePayload AS candidatePayload,
            (SELECT COUNT(*)
             FROM raw_events AS observed
             WHERE observed.connectorId = r.connectorId
               AND observed.contentHash = r.contentHash
               AND observed.captureScope = r.captureScope) AS matchingObservationCount
        FROM source_draft_proposals AS p
        INNER JOIN parse_attempts AS a ON a.id = p.parseAttemptId
        INNER JOIN raw_events AS r ON r.id = p.rawEventId
        WHERE p.state = 'WAITING_USER'
        ORDER BY p.createdAtEpochMillis DESC, p.id DESC
        """,
    )
    fun observePendingProposalRows(): Flow<List<SourceProposalRow>>

    @Query(
        """
        SELECT
            p.id AS proposalId,
            p.state AS proposalState,
            p.createdAtEpochMillis AS proposalCreatedAtEpochMillis,
            r.id AS rawEventId,
            r.sourceFamily AS sourceFamily,
            r.connectorId AS rawConnectorId,
            r.captureMethod AS captureMethod,
            r.capturedAtEpochMillis AS capturedAtEpochMillis,
            a.id AS parseAttemptId,
            a.rawEventId AS attemptRawEventId,
            a.parserId AS parserId,
            a.providerId AS providerId,
            a.sourceFamily AS attemptSourceFamily,
            a.connectorId AS connectorId,
            a.parserVersion AS parserVersion,
            a.ruleVersion AS ruleVersion,
            a.outcome AS parseOutcome,
            a.diagnosticCode AS diagnosticCode,
            a.diagnosticRecoverable AS diagnosticRecoverable,
            a.candidatePayload AS candidatePayload,
            (SELECT COUNT(*)
             FROM raw_events AS observed
             WHERE observed.connectorId = r.connectorId
               AND observed.contentHash = r.contentHash
               AND observed.captureScope = r.captureScope) AS matchingObservationCount
        FROM source_draft_proposals AS p
        INNER JOIN parse_attempts AS a ON a.id = p.parseAttemptId
        INNER JOIN raw_events AS r ON r.id = p.rawEventId
        WHERE p.id = :id
        LIMIT 1
        """,
    )
    suspend fun findProposalRow(id: String): SourceProposalRow?

    @Query(
        """
        UPDATE source_draft_proposals
        SET state = 'COMPLETED',
            completedDraftId = :draftId
        WHERE id = :proposalId
          AND state = 'WAITING_USER'
          AND completedDraftId IS NULL
        """,
    )
    suspend fun completeProposal(proposalId: String, draftId: String): Int

    @Query(
        """
        UPDATE source_draft_proposals
        SET state = 'DISMISSED'
        WHERE id = :proposalId
          AND state = 'WAITING_USER'
          AND completedDraftId IS NULL
        """,
    )
    suspend fun dismissProposal(proposalId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDraftSourceEvidence(evidence: DraftSourceEvidenceEntity)

    @Query("SELECT * FROM draft_source_evidence WHERE draftId = :draftId")
    suspend fun findDraftSourceEvidence(draftId: String): DraftSourceEvidenceEntity?

    @Query(
        """
        SELECT
            (SELECT COUNT(*)
             FROM source_draft_proposals
             WHERE completedDraftId = :draftId)
            +
            (SELECT COUNT(*)
             FROM draft_source_evidence
             WHERE draftId = :draftId)
            -
            (2 * (
                SELECT COUNT(*)
                FROM draft_source_evidence AS e
                INNER JOIN source_draft_proposals AS p ON p.id = e.proposalId
                INNER JOIN parse_attempts AS a ON a.id = e.parseAttemptId
                INNER JOIN raw_events AS r ON r.id = e.rawEventId
                WHERE e.draftId = :draftId
                  AND p.state = 'COMPLETED'
                  AND p.completedDraftId = e.draftId
                  AND p.rawEventId = e.rawEventId
                  AND p.parseAttemptId = e.parseAttemptId
                  AND a.rawEventId = e.rawEventId
                  AND a.sourceFamily = r.sourceFamily
                  AND a.connectorId = r.connectorId
                  AND r.sourceFamily IN ('ALIPAY', 'WECHAT', 'BANK', 'GENERIC', 'MANUAL')
                  AND r.captureMethod IN (
                      'NOTIFICATION',
                      'STATEMENT_IMPORT',
                      'SHARE_TEXT',
                      'SHARE_FILE',
                      'PHOTO_OCR',
                      'MANUAL'
                  )
                  AND TRIM(r.id) != ''
                  AND TRIM(r.connectorId) != ''
                  AND TRIM(r.captureScope) != ''
                  AND TRIM(r.payloadReference) != ''
                  AND LENGTH(r.contentHash) = 64
                  AND r.contentHash NOT GLOB '*[^0-9a-f]*'
                  AND a.outcome IN ('PARSED', 'NEEDS_USER_REVIEW')
                  AND TRIM(a.parserId) != ''
                  AND TRIM(a.providerId) != ''
                  AND TRIM(a.connectorId) != ''
                  AND TRIM(a.parserVersion) != ''
                  AND TRIM(a.ruleVersion) != ''
                  AND (
                      (a.outcome = 'PARSED' AND a.diagnosticCode IS NULL)
                      OR
                      (
                          a.outcome = 'NEEDS_USER_REVIEW'
                          AND a.diagnosticCode IS NOT NULL
                          AND a.diagnosticRecoverable IS NOT NULL
                      )
                  )
            ))
        """,
    )
    suspend fun countDraftSourceLinkIssues(draftId: String): Long

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM raw_events
             WHERE sourceFamily NOT IN ('ALIPAY', 'WECHAT', 'BANK', 'GENERIC', 'MANUAL')
                OR captureMethod NOT IN (
                    'NOTIFICATION',
                    'STATEMENT_IMPORT',
                    'SHARE_TEXT',
                    'SHARE_FILE',
                    'PHOTO_OCR',
                    'MANUAL'
                )
                OR TRIM(id) = ''
                OR TRIM(connectorId) = ''
                OR TRIM(captureScope) = ''
                OR TRIM(payloadReference) = ''
                OR LENGTH(contentHash) != 64
                OR contentHash GLOB '*[^0-9a-f]*')
            +
            (SELECT COUNT(*) FROM parse_attempts
             WHERE outcome NOT IN ('PARSED', 'NEEDS_USER_REVIEW', 'REJECTED')
                OR TRIM(parserId) = ''
                OR TRIM(providerId) = ''
                OR TRIM(connectorId) = ''
                OR TRIM(parserVersion) = ''
                OR TRIM(ruleVersion) = ''
                OR (
                    outcome = 'PARSED'
                    AND (
                        diagnosticCode IS NOT NULL
                        OR candidatePayload IS NULL
                    )
                )
                OR (
                    outcome = 'NEEDS_USER_REVIEW'
                    AND diagnosticCode IS NULL
                )
                OR (
                    outcome = 'REJECTED'
                    AND (
                        diagnosticCode IS NULL
                        OR candidatePayload IS NOT NULL
                    )
                )
                OR (
                    diagnosticCode IS NULL
                    AND diagnosticRecoverable IS NOT NULL
                )
                OR (
                    diagnosticCode IS NOT NULL
                    AND diagnosticRecoverable IS NULL
                )
                OR (
                    diagnosticCode IS NOT NULL
                    AND diagnosticCode NOT IN (
                        'NO_MATCHING_PARSER',
                        'AMBIGUOUS_PARSER',
                        'SOURCE_NOT_ACCEPTED',
                        'EVIDENCE_NOT_FOUND',
                        'EVIDENCE_READ_FAILED',
                        'EVIDENCE_TOO_LARGE',
                        'EVIDENCE_INTEGRITY_MISMATCH',
                        'UNSUPPORTED_MEDIA_TYPE',
                        'MALFORMED_EVIDENCE',
                        'INSUFFICIENT_FIELDS',
                        'PARSER_RUNTIME_FAILURE',
                        'RAW_EVENT_ID_COLLISION',
                        'COMMIT_CONFLICT',
                        'COMMIT_REJECTED'
                    )
                ))
            +
            (SELECT COUNT(*)
             FROM parse_attempts AS a
             WHERE (
                 a.outcome IN ('PARSED', 'NEEDS_USER_REVIEW')
                 AND (SELECT COUNT(*) FROM source_draft_proposals AS p
                      WHERE p.parseAttemptId = a.id) != 1
             ) OR (
                 a.outcome = 'REJECTED'
                 AND EXISTS (
                     SELECT 1 FROM source_draft_proposals AS p
                     WHERE p.parseAttemptId = a.id
                 )
             ))
            +
            (SELECT COUNT(*) FROM source_draft_proposals
             WHERE state NOT IN ('WAITING_USER', 'COMPLETED', 'DISMISSED')
                OR (state = 'WAITING_USER' AND completedDraftId IS NOT NULL)
                OR (state = 'COMPLETED' AND completedDraftId IS NULL)
                OR (state = 'DISMISSED' AND completedDraftId IS NOT NULL))
            +
            (SELECT COUNT(*)
             FROM parse_attempts AS a
             INNER JOIN raw_events AS r ON r.id = a.rawEventId
             WHERE a.sourceFamily != r.sourceFamily
                OR a.connectorId != r.connectorId)
            +
            (SELECT COUNT(*)
             FROM source_draft_proposals AS p
             INNER JOIN parse_attempts AS a ON a.id = p.parseAttemptId
             WHERE p.rawEventId != a.rawEventId)
            +
            (SELECT COUNT(*)
             FROM draft_source_evidence AS e
             INNER JOIN source_draft_proposals AS p ON p.id = e.proposalId
             INNER JOIN parse_attempts AS a ON a.id = e.parseAttemptId
             WHERE e.rawEventId != p.rawEventId
                OR e.parseAttemptId != p.parseAttemptId
                OR e.rawEventId != a.rawEventId
                OR p.state != 'COMPLETED'
                OR p.completedDraftId != e.draftId)
            +
            (SELECT COUNT(*)
             FROM source_draft_proposals AS p
             WHERE (
                 p.state = 'COMPLETED'
                 AND (SELECT COUNT(*) FROM draft_source_evidence AS e
                      WHERE e.proposalId = p.id
                        AND e.draftId = p.completedDraftId) != 1
             ) OR (
                 p.state != 'COMPLETED'
                 AND EXISTS (
                     SELECT 1 FROM draft_source_evidence AS e
                     WHERE e.proposalId = p.id
                 )
             ))
        """,
    )
    fun observeSourceIntegrityIssueCount(): Flow<Long>
}
