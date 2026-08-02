package dev.bill.app.quickcapture

import android.content.Context

/**
 * Persists only one safe capture outcome so a background Toast is not the sole feedback channel.
 *
 * No screenshot, OCR text, amount, provider, account, proposal identifier or timestamp from the
 * source page is stored. The wall-clock value exists only to expire this UI receipt.
 */
internal object QuickCaptureOutcomeStore {
    private const val PreferencesName = "quick-capture-outcome"
    private const val KeyType = "type"
    private const val KeyAlreadyPresent = "already-present"
    private const val KeyFailure = "failure"
    private const val KeyRecordedAt = "recorded-at"
    private const val KeyUnread = "unread"
    private const val SavedType = "saved"
    private const val FailedType = "failed"
    private const val ReceiptLifetimeMillis = 24L * 60L * 60L * 1_000L
    private const val FutureClockToleranceMillis = 5L * 60L * 1_000L

    @Synchronized
    fun record(
        context: Context,
        outcome: QuickCaptureOutcome,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val editor = preferences(context).edit().clear()
            .putLong(KeyRecordedAt, nowEpochMillis)
            .putBoolean(KeyUnread, true)
        when (outcome) {
            is QuickCaptureOutcome.Saved -> editor
                .putString(KeyType, SavedType)
                .putBoolean(KeyAlreadyPresent, outcome.alreadyPresent)

            is QuickCaptureOutcome.Failed -> editor
                .putString(KeyType, FailedType)
                .putString(KeyFailure, outcome.reason.name)
        }
        editor.apply()
    }

    @Synchronized
    fun consumeUnread(
        context: Context,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): QuickCaptureOutcome? {
        val preferences = preferences(context)
        if (!preferences.getBoolean(KeyUnread, false)) return null
        val recordedAt = preferences.getLong(KeyRecordedAt, Long.MIN_VALUE)
        val age = nowEpochMillis - recordedAt
        if (
            recordedAt == Long.MIN_VALUE ||
            age < -FutureClockToleranceMillis ||
            age > ReceiptLifetimeMillis
        ) {
            preferences.edit().clear().apply()
            return null
        }
        val outcome = when (preferences.getString(KeyType, null)) {
            SavedType -> QuickCaptureOutcome.Saved(
                alreadyPresent = preferences.getBoolean(KeyAlreadyPresent, false),
            )

            FailedType -> preferences.getString(KeyFailure, null)
                ?.let { name ->
                    runCatching { QuickCaptureFailure.valueOf(name) }.getOrNull()
                }
                ?.let { reason -> QuickCaptureOutcome.Failed(reason) }

            else -> null
        }
        if (outcome == null) {
            preferences.edit().clear().apply()
            return null
        }
        preferences.edit().putBoolean(KeyUnread, false).apply()
        return outcome
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )
}
