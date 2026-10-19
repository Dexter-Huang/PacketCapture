package com.packetcapture.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.packetcapture.core.*
import java.net.URI

/** Wenxin 5.20.0.11 normal text/orion stream. Transport cancellation remains a failure. */
internal class WenxinDecoder : AnswerDecoder {
    override val id = "wenxin-chat-sse/1"

    override fun matches(exchange: HttpExchange): Boolean = runCatching {
        val uri = URI(exchange.url)
        exchange.method == "POST" && uri.scheme == "https" && uri.host == "yiyanapp.baidu.com" &&
            uri.path == "/chat/completions" && uri.port in setOf(-1, 443) && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)

    override fun decode(exchange: HttpExchange, request: BodyPreview, response: BodyPreview): CapturedAnswer {
        val issues = linkedSetOf<String>()
        val req = if (request.binary || request.limited) null else wxJson(request.rawText ?: request.text)
        val prompt = req?.wxString("content")
        if (prompt.isNullOrBlank()) issues += "prompt_unavailable"
        val models = req?.wxObj("models")
        if (req == null || models == null || req.wxBool("newBot") == null ||
            listOf("botId", "characterId", "sessionId", "parentId").any { req.wxString(it) == null })
            issues += "request_schema_unavailable"
        val supportedRequest = req == null || (req.wxString("contentType") == "text" &&
            req.wxString("inputSource") == "user_input" && req.wxString("querySource") == "user_input" &&
            req.wxString("variantMode") == "conversation" && req.wxInt("isTemporaryChat") == 0 &&
            req.wxInt("mainAssistant") in setOf(0, 1) && req.wxObj("plugins")?.size() == 0 &&
            req.get("chooses").let { it == null || it.isJsonNull } &&
            req.get("context")?.let { it.isJsonArray && it.asJsonArray.all { item -> item.isJsonPrimitive && item.asJsonPrimitive.isString } } == true &&
            models?.wxString("select") == "auto" && models.wxString("dispatch") == "auto" &&
            models.wxBool("normal") == true && models.wxString("replyMode") == "normal")
        if (!supportedRequest) issues += "unsupported_request_protocol"
        val newSession = req?.wxBool("newBot")
        if (newSession != null && listOf("botId", "sessionId", "parentId").any { key ->
                req?.wxString(key)?.let { if (newSession) it !in setOf("", "0") else it.isBlank() || it == "0" } != false })
            issues += "request_identity_unavailable"
        val state = StreamState(req)
        val stream = ServerSentEvents.parse(if (response.binary) "" else response.rawText ?: response.text, Int.MAX_VALUE)
        stream.events.forEach(state::accept)
        issues += state.issues
        if (request.binary || response.binary || request.limited || response.limited ||
            listOfNotNull(exchange.requestBody, exchange.responseBody).any { it.truncated || it.observedBytes > it.savedBytes })
            issues += "body_unavailable_or_limited"
        if (exchange.completion != Completion.COMPLETE || exchange.endedAt == null ||
            exchange.requestBody == null || exchange.responseBody == null ||
            exchange.requestBody?.inProgress == true || exchange.responseBody?.inProgress == true) issues += "transport_incomplete"
        if (!exchange.error.isNullOrBlank() || exchange.status !in 200..299) issues += "transport_error"
        if (!state.finished) issues += "missing_terminal_marker"
        if (stream.pending) issues += "pending_sse_frame"
        if (state.answer.isBlank()) issues += "empty_answer"
        val status = when {
            state.remoteError || "transport_error" in issues || exchange.completion == Completion.FAILED -> "failed"
            state.unknown || !supportedRequest -> "unsupported"
            issues.isEmpty() -> "complete"
            else -> "incomplete"
        }
        return CapturedAnswer(provider = "wenxin", sessionId = exchange.sessionId, exchangeId = exchange.id,
            packageName = exchange.packageName, startedAt = exchange.startedAt, endedAt = exchange.endedAt,
            decoder = id, prompt = prompt, answer = state.answer.toString(), status = status,
            searchSources = state.sources.values.toList(), referenceCaptureStatus = if (state.sources.isEmpty()) "not_collected" else "collected",
            diagnostics = issues.toList(), eventCount = stream.totalEvents)
    }

    private class StreamState(private val request: JsonObject?) {
        val answer = StringBuilder()
        val sources = linkedMapOf<String, SearchSource>()
        val issues = linkedSetOf<String>()
        var unknown = false
            private set
        var remoteError = false
            private set
        var finished = false
            private set
        private var identity: Map<String, String>? = null
        private var nextChunk = 0

        private fun schema(valid: Boolean, diagnostic: String = "unrecognized_event"): Boolean {
            if (!valid) { unknown = true; issues += diagnostic }
            return valid
        }
        private fun failed() { remoteError = true; issues += "remote_error" }

        fun accept(event: SseEvent) {
            val obj = wxJson(event.data)
            if (event.type.contains("error", ignoreCase = true) || obj?.wxInt("errno")?.let { it != 0 } == true ||
                obj?.wxString("errmsg")?.let { it != "success" } == true) { failed(); return }
            if (!schema(!finished, "event_after_done")) return
            if (!schema(event.type == "message" && obj?.wxInt("errno") == 0 && obj.wxString("errmsg") == "success")) return
            val outer = obj!!.wxObj("data")
            val data = outer?.wxObj("data")
            if (!schema(data != null)) return
            when (outer!!.wxInt("type")) {
                1 -> initialize(data!!)
                2 -> chunk(data!!)
                else -> schema(false)
            }
        }

        private fun initialize(data: JsonObject) {
            if (!schema(identity == null && nextChunk == 0)) return
            if (!schema(data.wxInt("isDeep") == 0 && data.wxInt("isDepthAnalysis") == 0 &&
                    data.wxString("mcpTool") == "" && data.wxInt("sessionDel") == 0 && data.wxInt("botType") in setOf(7, 8),
                    "unsupported_response_protocol")) return
            val ask = data.wxObj("messageInfos")?.wxObj("ask")
            val reply = data.wxObj("messageInfos")?.wxObj("answer")
            val keys = listOf("botId", "characterId", "sessionId", "promptId")
            val id = keys.mapNotNull { key -> data.wxString(key)?.takeIf { it.isNotBlank() && (key == "promptId" || it != "0") }?.let { key to it } }.toMap()
            val messageId = reply?.wxString("messageId")
            val parentId = ask?.wxString("messageId")
            if (!schema(id.size == keys.size && !messageId.isNullOrBlank() && !parentId.isNullOrBlank() &&
                    messageId != "0" && parentId != "0" && messageId != parentId && reply.wxString("parentId") == parentId &&
                    (request == null || data.wxString("characterId") == request.wxString("characterId")), "stream_identity_mismatch")) return
            if (request?.wxBool("newBot") == false && !schema(data.wxString("sessionId") == request.wxString("sessionId") &&
                    data.wxString("botId") == request.wxString("botId") && ask!!.wxString("parentId") == request.wxString("parentId"),
                    "stream_identity_mismatch")) return
            identity = id + mapOf("messageId" to messageId!!, "parentId" to parentId!!)
        }

        private fun chunk(data: JsonObject) {
            val id = identity
            if (!schema(id != null && id.all { (key, value) -> data.wxString(key) == value }, "stream_identity_mismatch")) return
            if (!schema(data.wxInt("chunkIndex") == nextChunk, "stream_sequence_mismatch")) return
            nextChunk++
            if (data.wxInt("isSafe")?.let { it != 1 } == true || data.wxInt("flag")?.let { it != 0 } == true ||
                data.wxInt("needClearHistory")?.let { it != 0 } == true) { failed(); return }
            if (!schema(data.wxString("content") == "" && data.wxString("endpoint") == "" &&
                    data.wxString("contentType") == "aigc" && data.wxString("askType") == "text" &&
                    data.wxString("replace") == "" && data.wxInt("finish") in setOf(0, 1) &&
                    data.wxInt("isSafe") == 1 && data.wxInt("flag") == 0 && data.wxInt("needClearHistory") == 0,
                    "unsupported_response_protocol")) return
            if (data.has("generator")) {
                val generator = data.wxObj("generator")
                if (!schema(generator != null)) return
                acceptGenerator(generator!!)
            }
            if (data.has("reference")) {
                val reference = data.wxObj("reference")
                if (schema(reference != null)) acceptReferences(reference!!)
            }
            if (data.wxInt("finish") == 1) finished = true
        }

        private fun acceptGenerator(generator: JsonObject) {
            val meta = generator.wxObj("meta")
            val gen = generator.wxObj("data")
            if (gen?.wxInt("antiFlag")?.let { it != 0 } == true || gen?.wxInt("isSafe")?.let { it != 1 } == true ||
                gen?.wxBool("needClearHistory") == true) { failed(); return }
            if (!schema(meta?.wxString("workflow") == "orion" && meta.wxBool("isIntervene") == false &&
                    gen?.wxInt("antiFlag") == 0 && gen.wxInt("isSafe") == 1 && gen.wxBool("needClearHistory") == false &&
                    gen.wxBool("isFinished") == false, "unsupported_response_protocol")) return
            val data = gen!!.wxObj("data")
            if (!schema(data != null)) return
            when (gen.wxString("component")) {
                "markdown-yiyan" -> if (schema(data!!.wxString("value") != null)) answer.append(data.wxString("value"))
                "thinkingSteps" -> schema(data!!.wxBool("noReasoning") == true &&
                    data.get("reasoningContentArr")?.let { it.isJsonArray && it.asJsonArray.all { v ->
                        v.isJsonPrimitive && v.asJsonPrimitive.isString && v.asString.isEmpty() } } == true,
                    "unsupported_reasoning_protocol")
                "input" -> schema(data!!.wxString("guideWord") != null && data.wxString("isDefaultPlaceholder") == "")
                else -> schema(false, "unsupported_component")
            }
        }

        private fun acceptReferences(reference: JsonObject) {
            val items = reference.get("searchInfo")?.takeIf { it.isJsonArray }?.asJsonArray
            if (!schema(items != null && items.all { it.isJsonObject })) return
            for (item in items!!) {
                val source = item.asJsonObject
                val title = source.wxString("title")
                val url = source.wxString("url")
                val index = source.wxInt("index")
                if (!schema(title != null && url != null && index != null && index > 0 && source.wxString("type") == "")) continue
                val validUrl = runCatching { URI(url).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
                if (validUrl) sources.putIfAbsent(url!!, SearchSource(title!!, url, index))
            }
        }
    }
}

private fun wxJson(text: String): JsonObject? = runCatching {
    JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
}.getOrNull()
private fun JsonObject.wxObj(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.wxString(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
private fun JsonObject.wxInt(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.toString()?.toIntOrNull()
private fun JsonObject.wxBool(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
