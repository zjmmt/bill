package dev.bill.source.genericnotification

/** Metadata is transient; it must not be copied into a RawEvent, payload, log or diagnostic. */
class NotificationMetadata(
    val packageName: String,
    val channelId: String?,
    val category: String?,
) {
    init {
        require(packageName.isNotBlank() && packageName.length <= MAX_METADATA_LENGTH) {
            "Notification package metadata is invalid"
        }
        require(channelId == null || (channelId.isNotBlank() && channelId.length <= MAX_METADATA_LENGTH)) {
            "Notification channel metadata is invalid"
        }
        require(category == null || (category.isNotBlank() && category.length <= MAX_METADATA_LENGTH)) {
            "Notification category metadata is invalid"
        }
    }

    override fun toString(): String = "NotificationMetadata(redacted=true)"

    private companion object {
        const val MAX_METADATA_LENGTH = 255
    }
}

/**
 * A locally bundled, source-specific rule. Production starts with an empty catalog until there
 * are sanitized fixtures; tests may supply synthetic templates.
 */
class NotificationTemplate(
    val id: String,
    val version: String,
    private val packageName: String,
    private val channelId: String? = null,
    private val category: String? = null,
    private val contentMatcher: (NotificationContent) -> Boolean,
) {
    init {
        require(id.matches(OPAQUE_TOKEN)) { "Notification template id is invalid" }
        require(version.matches(OPAQUE_TOKEN)) { "Notification template version is invalid" }
        require(packageName.isNotBlank() && packageName.length <= MAX_METADATA_LENGTH) {
            "Notification template package is invalid"
        }
        require(channelId != null && channelId.isNotBlank() && channelId.length <= MAX_METADATA_LENGTH) {
            "Notification templates must select one Android notification channel"
        }
        require(category == null || (category.isNotBlank() && category.length <= MAX_METADATA_LENGTH)) {
            "Notification template category is invalid"
        }
    }

    fun matchesMetadata(metadata: NotificationMetadata): Boolean =
        metadata.packageName == packageName &&
            metadata.channelId == channelId &&
            metadata.category == category

    fun matchesContent(content: NotificationContent): Boolean = contentMatcher(content)

    override fun toString(): String = "NotificationTemplate(id=$id, version=$version)"

    private companion object {
        val OPAQUE_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        const val MAX_METADATA_LENGTH = 255
    }
}

sealed interface NotificationGateDecision {
    data object IgnoredMetadata : NotificationGateDecision

    data object IgnoredContent : NotificationGateDecision

    data object AmbiguousContent : NotificationGateDecision

    class Accepted internal constructor(
        val route: VerifiedNotificationRoute,
        val content: NotificationContent,
    ) : NotificationGateDecision {
        override fun toString(): String = "NotificationGateDecision.Accepted(${route.routeId})"
    }
}

/**
 * Selects candidate templates using non-body metadata before it can invoke [readContent]. Android
 * has no trustworthy package-only NotificationListener allowlist, so this gate is the mandatory
 * in-process boundary between an all-app callback and the sensitive notification extras.
 */
class NotificationTemplateGate(
    private val catalog: NotificationRouteCatalog,
    /** A catalog route is inert unless its owning app supplies an explicit local opt-in. */
    private val isRouteEnabled: (String) -> Boolean = { false },
) {

    fun evaluate(
        metadata: NotificationMetadata,
        readContent: () -> NotificationContent?,
    ): NotificationGateDecision {
        val candidates = candidatesFor(metadata)
        if (candidates.isEmpty()) return NotificationGateDecision.IgnoredMetadata

        val content = readContent() ?: return NotificationGateDecision.IgnoredContent
        val matches = candidates.filter { route ->
            runCatching { route.template.matchesContent(content) }.getOrDefault(false)
        }
        return when (matches.size) {
            0 -> NotificationGateDecision.IgnoredContent
            1 -> NotificationGateDecision.Accepted(matches.single(), content)
            else -> NotificationGateDecision.AmbiguousContent
        }
    }

    /**
     * This may be called from the system callback before reading Android extras. It exposes only
     * whether a locally bundled package/channel/category rule could apply; it never evaluates a
     * body matcher or returns template metadata to a caller.
     */
    fun hasMetadataCandidate(metadata: NotificationMetadata): Boolean =
        candidatesFor(metadata).isNotEmpty()

    /** Exposes catalog readiness without exposing package/channel rules to the UI layer. */
    fun hasVerifiedTemplates(): Boolean = catalog.hasVerifiedRoutes()

    private fun candidatesFor(metadata: NotificationMetadata): List<VerifiedNotificationRoute> =
        catalog.routes.filter { route ->
            runCatching { isRouteEnabled(route.routeId) }.getOrDefault(false) &&
                route.template.matchesMetadata(metadata)
        }
}
