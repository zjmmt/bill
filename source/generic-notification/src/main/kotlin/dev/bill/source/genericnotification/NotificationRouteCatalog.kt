package dev.bill.source.genericnotification

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.SafeDiagnostic
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.SourceParser

/**
 * A static, locally bundled route that has already passed source-specific review. Its private
 * Android metadata stays inside [NotificationTemplate]; only the opaque route id and safe label
 * may cross into app settings or review presentation.
 */
class VerifiedNotificationRoute(
    val routeId: String,
    val sourceIdentity: SourceIdentity,
    val template: NotificationTemplate,
    val safeLabel: String,
    parserFactory: ((VerifiedNotificationRoute) -> SourceParser)? = null,
) {
    private val resolvedParser: SourceParser by lazy(LazyThreadSafetyMode.PUBLICATION) {
        (parserFactory?.invoke(this) ?: NotificationRouteParser(this)).also { parser ->
            require(parser.identity == sourceIdentity) {
                "Notification route parser identity must equal its route identity"
            }
        }
    }

    init {
        require(routeId.matches(OPAQUE_TOKEN)) { "Notification route id is invalid" }
        require(template.id == routeId) { "Notification template id must equal its route id" }
        require(sourceIdentity.parserId.value == routeId) {
            "Notification parser id must equal its route id"
        }
        require(sourceIdentity.connectorId.value == routeId) {
            "Notification connector id must equal its route id"
        }
        require(sourceIdentity.supportedCaptureMethods == setOf(CaptureMethod.NOTIFICATION)) {
            "Notification routes must support only notification capture"
        }
        require(
            safeLabel.isNotBlank() &&
                safeLabel.length <= MAX_SAFE_LABEL_LENGTH &&
                safeLabel.none(Char::isISOControl),
        ) { "Notification route safe label is invalid" }
    }

    override fun toString(): String = "VerifiedNotificationRoute(routeId=$routeId)"

    internal fun parser(): SourceParser = resolvedParser

    private companion object {
        val OPAQUE_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        const val MAX_SAFE_LABEL_LENGTH = 80
    }
}

/** A non-sensitive representation suitable for the app's settings and review presentation. */
data class NotificationRoutePresentation(
    val routeId: String,
    val safeLabel: String,
)

/**
 * The single source of truth for routes, parser registration and safe labels. Every production
 * route is locally bundled, sample-backed and inert until its individual local opt-in is enabled.
 */
class NotificationRouteCatalog(routes: Iterable<VerifiedNotificationRoute>) {
    internal val routes: List<VerifiedNotificationRoute> = routes.toList().also { loaded ->
        require(loaded.map(VerifiedNotificationRoute::routeId).toSet().size == loaded.size) {
            "Notification route ids must be unique"
        }
        require(loaded.map { it.sourceIdentity.parserId }.toSet().size == loaded.size) {
            "Notification route parser ids must be unique"
        }
        require(loaded.map { it.sourceIdentity.connectorId }.toSet().size == loaded.size) {
            "Notification route connector ids must be unique"
        }
        require(
            loaded.map { it.template.id to it.template.version }.toSet().size == loaded.size,
        ) { "Notification route template id/version pairs must be unique" }
        require(
            loaded.none { route ->
                route.sourceIdentity.sourceFamily == SourceFamily.GENERIC &&
                    route.sourceIdentity.connectorId == GENERIC_NOTIFICATION_CONNECTOR_ID
            },
        ) {
            "Notification routes must not collide with the generic notification parser"
        }
        loaded.forEach(VerifiedNotificationRoute::parser)
    }

    fun hasVerifiedRoutes(): Boolean = routes.isNotEmpty()

    fun presentations(): List<NotificationRoutePresentation> = routes.map { route ->
        NotificationRoutePresentation(route.routeId, route.safeLabel)
    }

    fun parsers(): List<SourceParser> = routes.map(VerifiedNotificationRoute::parser)

    /** A capture hand-off must be an exact member of this static catalog, not a caller-built lookalike. */
    fun contains(route: VerifiedNotificationRoute): Boolean = routes.any { candidate ->
        candidate === route
    }

    fun safeLabelForConnector(connectorId: String): String? = routes
        .firstOrNull { route -> route.sourceIdentity.connectorId.value == connectorId }
        ?.safeLabel

    companion object {
        fun empty(): NotificationRouteCatalog = NotificationRouteCatalog(emptyList())
    }
}

/**
 * A deliberately conservative parser for a route. It proves that the accepted route identity
 * survives transport intact, but never turns unreviewed notification text into financial facts.
 * A later source-specific parser may replace this only with fixture-backed parsing rules.
 */
class NotificationRouteParser(
    private val route: VerifiedNotificationRoute,
) : SourceParser {
    override val identity: SourceIdentity = route.sourceIdentity

    override fun parse(rawEvent: dev.bill.source.contract.RawEvent, evidenceInput: EvidenceInput): ParseResult {
        if (!identity.accepts(rawEvent)) return rejected(DiagnosticCode.SOURCE_NOT_ACCEPTED)
        if (evidenceInput.mediaType != NotificationEvidenceMediaTypes.ENVELOPE) {
            return rejected(DiagnosticCode.UNSUPPORTED_MEDIA_TYPE)
        }
        if (evidenceInput.sizeBytes > NotificationEnvelopeCodec.MAX_ENCODED_BYTES) {
            return rejected(DiagnosticCode.EVIDENCE_TOO_LARGE)
        }
        val bytes = evidenceInput.copyBytes()
        val decoded = try {
            NotificationEnvelopeCodec.decode(bytes)
        } finally {
            bytes.fill(0)
        }
        val envelope = (decoded as? NotificationEnvelopeDecodeResult.Decoded)?.envelope
            ?: return rejected(DiagnosticCode.MALFORMED_EVIDENCE)
        if (
            envelope.templateId != route.routeId ||
                envelope.templateVersion != route.template.version
        ) {
            return rejected(DiagnosticCode.SOURCE_NOT_ACCEPTED)
        }
        return ParseResult.NeedsUserReview(
            candidate = null,
            diagnostic = SafeDiagnostic(
                code = DiagnosticCode.INSUFFICIENT_FIELDS,
                recoverable = true,
            ),
        )
    }

    private fun rejected(code: DiagnosticCode) = ParseResult.Rejected(
        SafeDiagnostic(code = code, recoverable = false),
    )
}
