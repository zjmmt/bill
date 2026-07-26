package dev.bill.source.contract

private val opaqueIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

private fun requireOpaqueId(value: String, label: String) {
    require(opaqueIdPattern.matches(value)) {
        "$label must be an opaque ASCII token between 1 and 128 characters"
    }
}

@JvmInline
value class RawEventId(val value: String) {
    init {
        requireOpaqueId(value, "Raw event id")
    }
}

@JvmInline
value class ConnectorId(val value: String) {
    init {
        requireOpaqueId(value, "Connector id")
    }
}

@JvmInline
value class ProviderId(val value: String) {
    init {
        requireOpaqueId(value, "Provider id")
    }
}

@JvmInline
value class CaptureScopeId(val value: String) {
    init {
        requireOpaqueId(value, "Capture scope id")
    }
}

/** An application-owned identifier, never a path, URI, or provider reference. */
@JvmInline
value class PayloadId(val value: String) {
    init {
        requireOpaqueId(value, "Payload id")
    }
}

@JvmInline
value class ParserId(val value: String) {
    init {
        requireOpaqueId(value, "Parser id")
    }
}

@JvmInline
value class VersionId(val value: String) {
    init {
        requireOpaqueId(value, "Version id")
    }
}
