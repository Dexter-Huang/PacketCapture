package com.packetcapture.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.packetcapture.core.*
import java.net.URI

/** Validated against the native 2.88.0 /api/chat/ SSE capture; not the history endpoint. */
internal class YuanbaoDecoder : AnswerDecoder {
    override val id = "yuanbao-chat-sse/1"

    override fun matches(exchange: HttpExchange): Boolean = runCatching {
        val uri = URI(exchange.url)
        exchange.method == "POST" && uri.scheme == "https" && uri.host == "api.yuanbao.tencent.com" &&
            uri.path.startsWith("/api/chat/") && uri.path.removePrefix("/api/chat/").isNotBlank()
    }.getOrDefault(false)

    override fun decode(exchange: HttpExchange, request: BodyPreview, response: BodyPreview): CapturedAnswer {
        val issues = mutableListOf<String>()
        val prompt = if (request.binary || request.limited) null else json(request.rawText ?: request.text)?.string("prompt")
        if (prompt == null) issues += "prompt_unavailable"
        val stream = ServerSentEvents.parse(response.rawText ?: response.text, Int.MAX_VALUE)
        val text = StringBuilder()
        val sources = linkedMapOf<String, SearchSource>()
        var done = false
        var stopped = false
        var remoteError = false
        var unknown = false
        for (event in stream.events) {
            if (done) { issues += "event_after_done"; continue }
            if (event.data.trim() == "[DONE]") { done = true; continue }
            // Non-JSON control frames do not carry answer text.
            if (event.type in setOf("speech_type", "plugin", "msgindex", "trace")) continue
            val obj = json(event.data)
            if (obj == null) {
                // Yuanbao emits bracketed bookkeeping frames; accept only known names.
                if (!Regex("\\[(plugin|MSGINDEX|TRACEID):[^\\r\\n]*]", RegexOption.IGNORE_CASE).matches(event.data)) unknown = true
                continue
            }
            when (obj.string("type")) {
                "text" -> {
                    if (obj.has("msg")) {
                        val delta = obj.string("msg")
                        if (delta == null) unknown = true else text.append(delta)
                    }
                }
                "meta" -> {
                    stopped = obj.string("stopReason") == "stop" && obj.int("modelErrorCode") == 0
                    remoteError = remoteError || obj.int("modelErrorCode")?.let { it != 0 } == true ||
                        !obj.string("modelErrorMsg").isNullOrEmpty() || obj.get("requireLogin")?.toString() == "true"
                }
                "searchGuid" -> {
                    val docs = obj.get("docs")
                    if (docs?.isJsonArray == true) for (element in docs.asJsonArray) {
                        if (!element.isJsonObject) continue
                        val doc = element.asJsonObject
                        val url = doc.string("url") ?: continue
                        val valid = runCatching { URI(url).let { it.scheme in setOf("http", "https") && !it.host.isNullOrEmpty() } }.getOrDefault(false)
                        if (valid) sources[url] = SearchSource(doc.string("title").orEmpty(), url, doc.int("index"))
                    }
                }
                "step", "speech_type", "hint_v2_tip" -> Unit
                else -> unknown = true
            }
        }
        if (response.binary || response.limited || request.limited || exchange.requestBody?.truncated == true ||
            exchange.responseBody?.truncated == true) issues += "body_unavailable_or_limited"
        if (exchange.completion != Completion.COMPLETE || exchange.endedAt == null ||
            exchange.responseBody == null || exchange.responseBody?.inProgress == true || exchange.requestBody?.inProgress == true) issues += "transport_incomplete"
        if (!exchange.error.isNullOrBlank() || exchange.status !in 200..299) issues += "transport_error"
        if (remoteError) issues += "remote_error"
        if (unknown) issues += "unrecognized_event"
        if (!done || !stopped) issues += "missing_terminal_marker"
        if (stream.pending) issues += "pending_sse_frame"
        if (text.isEmpty()) issues += "empty_answer"
        val status = when {
            remoteError || "transport_error" in issues || exchange.completion == Completion.FAILED -> "failed"
            unknown -> "unsupported"
            issues.isEmpty() -> "complete"
            else -> "incomplete"
        }
        return CapturedAnswer(provider = "yuanbao", sessionId = exchange.sessionId, exchangeId = exchange.id,
            packageName = exchange.packageName, startedAt = exchange.startedAt, endedAt = exchange.endedAt,
            decoder = id, prompt = prompt, answer = text.toString(), searchSources = sources.values.toList(),
            referenceCaptureStatus = if (sources.isEmpty()) "not_collected" else "collected",
            status = status, diagnostics = issues.distinct(), eventCount = stream.totalEvents)
    }

    private fun json(text: String): JsonObject? = runCatching {
        JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.int(key: String): Int? = runCatching { get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt }.getOrNull()
}
