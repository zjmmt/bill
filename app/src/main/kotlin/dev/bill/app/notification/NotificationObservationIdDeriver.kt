package dev.bill.app.notification

import android.content.Context
import android.util.Base64
import dev.bill.source.contract.NotificationObservationId
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Derives a durable opaque observation id without persisting the Android notification key. */
internal fun interface NotificationObservationIdDeriver {
    fun derive(
        notificationKey: String,
        postedAtEpochMillis: Long,
    ): NotificationObservationId?
}

/**
 * Pure HMAC derivation used by the Android private-preference wrapper and unit tests. The caller
 * owns the supplied secret array; this class copies no notification key into persistent state.
 */
internal class HmacNotificationObservationIdDeriver(
    private val secretSupplier: () -> ByteArray?,
) : NotificationObservationIdDeriver {
    override fun derive(
        notificationKey: String,
        postedAtEpochMillis: Long,
    ): NotificationObservationId? {
        if (
            notificationKey.isBlank() ||
            notificationKey.length > MaxNotificationKeyCharacters ||
            postedAtEpochMillis < 0L
        ) {
            return null
        }
        val secret = secretSupplier() ?: return null
        val keyBytes = notificationKey.toByteArray(StandardCharsets.UTF_8)
        val timestampBytes = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(postedAtEpochMillis).array()
        try {
            val mac = Mac.getInstance(HmacAlgorithm)
            mac.init(SecretKeySpec(secret, HmacAlgorithm))
            mac.update(DomainSeparator)
            mac.update(0)
            mac.update(keyBytes)
            mac.update(timestampBytes)
            return NotificationObservationId(mac.doFinal().toLowerHex())
        } catch (_: Exception) {
            return null
        } finally {
            secret.fill(0)
            keyBytes.fill(0)
            timestampBytes.fill(0)
        }
    }

    private companion object {
        const val HmacAlgorithm = "HmacSHA256"
        const val MaxNotificationKeyCharacters = 1024
        val DomainSeparator = "dev.bill.notification-observation.v1".toByteArray(
            StandardCharsets.US_ASCII,
        )
    }
}

/**
 * The only persistent secret is stored in this app's private storage. It is not a network token,
 * is excluded from backups with the rest of app data, and is used only to make database digests
 * non-reversible outside this installation.
 */
internal class AppPrivateNotificationObservationIdDeriver(
    context: Context,
) : NotificationObservationIdDeriver {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )
    private val delegate = HmacNotificationObservationIdDeriver(::secret)

    override fun derive(
        notificationKey: String,
        postedAtEpochMillis: Long,
    ): NotificationObservationId? = delegate.derive(notificationKey, postedAtEpochMillis)

    private fun secret(): ByteArray? = synchronized(preferences) {
        preferences.getString(SecretKey, null)?.let { encoded ->
            decodeSecret(encoded)?.let { return@synchronized it }
        }

        val generated = ByteArray(SecretBytes).also(SecureRandom()::nextBytes)
        val encoded = Base64.encodeToString(generated, Base64.NO_WRAP)
        if (preferences.edit().putString(SecretKey, encoded).commit()) {
            generated
        } else {
            generated.fill(0)
            null
        }
    }

    private fun decodeSecret(encoded: String): ByteArray? = try {
        val decoded = Base64.decode(encoded, Base64.NO_WRAP)
        if (decoded.size == SecretBytes) {
            decoded
        } else {
            decoded.fill(0)
            null
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val PreferencesName = "notification-observation-v1"
        const val SecretKey = "install-hmac-secret"
        const val SecretBytes = 32
    }
}

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
