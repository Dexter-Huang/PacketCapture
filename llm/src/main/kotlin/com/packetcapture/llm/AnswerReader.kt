package com.packetcapture.llm

import com.google.gson.GsonBuilder
import com.packetcapture.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The only entry point for UI/automation: selection, decoding and quality gates live here. */
class AnswerReader(private val repository: CaptureRepository, private val bodies: BodyStore) {
    private val decoders: Map<String, AnswerDecoder> = mapOf("yuanbao" to YuanbaoDecoder())

    suspend fun read(sessionId: String, selector: AnswerSelector = AnswerSelector()): AnswerBatch = withContext(Dispatchers.IO) {
        val session = repository.session(sessionId) ?: error("Session not found")
        val results = mutableListOf<CapturedAnswer>()
        val connections = repository.allConnections(sessionId).associateBy { it.id }
        // allExchanges intentionally bypasses the UI's 200-row window.
        for (stored in repository.allExchanges(sessionId).sortedBy { it.startedAt }) {
            if (stored.rawTransport || !stored.responseHeaders.isEventStream() || stored.startedAt < selector.startedAfter ||
                (selector.exchangeId != null && stored.id != selector.exchangeId)) continue
            val pkg = stored.packageName?.takeIf { it.isNotBlank() }
                ?: connections[stored.connectionId]?.packageName?.takeIf { it.isNotBlank() }
                ?: session.packages.singleOrNull()
            val platform = PlatformCatalog.forPackage(pkg) ?: continue
            if (selector.provider != null && selector.provider != platform.id) continue
            val exchange = stored.copy(packageName = pkg)
            val decoder = decoders[platform.id]?.takeIf { it.matches(exchange) }
            val result = if (decoder == null) CapturedAnswer(provider = platform.id, sessionId = sessionId, exchangeId = exchange.id,
                packageName = pkg, startedAt = exchange.startedAt, endedAt = exchange.endedAt, decoder = null, prompt = null,
                answer = "", status = "unsupported", diagnostics = listOf("no_matching_decoder"), eventCount = 0)
            else decoder.decode(exchange, bodies.preview(exchange.requestBody, exchange.requestHeaders), bodies.preview(exchange.responseBody, exchange.responseHeaders))
            if (selector.prompt == null || result.prompt == selector.prompt) results += result
        }
        AnswerBatch(sessionId = sessionId, results = results, selectionStatus = when (results.size) {
            0 -> "none"
            1 -> "unique_${results.single().status}"
            else -> "ambiguous"
        })
    }
}

/** Shared by on-device export and the ADB bridge; versioned DTOs, no storage implementation details. */
object AnswerJson {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create()
    fun encode(value: Any): String = gson.toJson(value)
}
