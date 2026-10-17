package com.packetcapture.llm

import com.packetcapture.core.BodyPreview
import com.packetcapture.core.HttpExchange

/** v1 contract: no cookies, authorization headers or complete request bodies. */
data class CapturedAnswer(
    val schemaVersion: Int = 1,
    val source: String = "android_capture_sse",
    val provider: String,
    val sessionId: String,
    val exchangeId: String,
    val packageName: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val decoder: String?,
    val prompt: String?,
    val answer: String,
    val reasoning: String = "",
    val searchSources: List<SearchSource> = emptyList(),
    val referenceCaptureStatus: String = "not_collected",
    // complete | incomplete | failed | unsupported; partial text is never a successful answer.
    val status: String,
    val diagnostics: List<String>,
    val eventCount: Int,
)

data class SearchSource(val title: String, val url: String, val index: Int?)

data class AnswerSelector(
    val provider: String? = null,
    val prompt: String? = null,
    val startedAfter: Long = 0,
    val exchangeId: String? = null,
) {
    init {
        require(provider == null || PlatformCatalog.byId(provider) != null) { "Unknown provider" }
        require(startedAfter >= 0) { "startedAfter must be epoch milliseconds" }
    }
}

data class AnswerBatch(
    val schemaVersion: Int = 1,
    val sessionId: String,
    // none | ambiguous | unique_complete | unique_incomplete | unique_failed | unique_unsupported
    val selectionStatus: String,
    val results: List<CapturedAnswer>,
)

/** Decoders consume already decompressed bodies, never sockets or Android APIs. */
internal interface AnswerDecoder {
    val id: String
    fun matches(exchange: HttpExchange): Boolean
    fun decode(exchange: HttpExchange, request: BodyPreview, response: BodyPreview): CapturedAnswer
}
