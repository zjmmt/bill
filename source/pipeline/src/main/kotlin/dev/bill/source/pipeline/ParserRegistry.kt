package dev.bill.source.pipeline

import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.SourceParser

class ParserRegistry(parsers: Iterable<SourceParser>) {
    private val registeredParsers = parsers.toList()

    fun resolve(rawEvent: RawEvent): ParserResolution {
        val matches = registeredParsers.filter { parser ->
            parser.identity.accepts(rawEvent)
        }
        return when (matches.size) {
            0 -> ParserResolution.NoMatch
            1 -> ParserResolution.Selected(matches.single())
            else -> ParserResolution.Ambiguous
        }
    }
}

sealed interface ParserResolution {
    data class Selected(val parser: SourceParser) : ParserResolution

    data object NoMatch : ParserResolution

    data object Ambiguous : ParserResolution
}
