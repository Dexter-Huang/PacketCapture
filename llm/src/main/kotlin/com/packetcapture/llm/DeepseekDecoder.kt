package com.packetcapture.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.packetcapture.core.*
import java.net.URI

/** DeepSeek 2.6.2 default text stream; reasoning and triggered searches remain unverified. */
internal class DeepseekDecoder : AnswerDecoder {
    override val id = "deepseek-chat-sse/1"

    override fun matches(exchange: HttpExchange): Boolean = runCatching {
        val uri = URI(exchange.url)
        exchange.method == "POST" && uri.scheme == "https" && uri.host == "chat.deepseek.com" &&
            uri.path == "/api/v0/chat/completion" && uri.port in setOf(-1, 443) && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)

    override fun decode(exchange: HttpExchange, request: BodyPreview, response: BodyPreview): CapturedAnswer {
        val issues = linkedSetOf<String>()
        val req = if (request.binary || request.limited) null else dsJson(request.rawText ?: request.text)
        val prompt = req?.dsString("prompt")
        if (prompt.isNullOrBlank()) issues += "prompt_unavailable"
        if (req?.dsString("chat_session_id").isNullOrBlank()) issues += "request_identity_unavailable"
        if (req?.dsString("model_type") == null || req.dsBool("thinking_enabled") == null ||
            req.dsBool("search_enabled") == null || req.get("ref_file_ids")?.isJsonArray != true) issues += "request_schema_unavailable"
        val supportedRequest = req == null || (
            (!req.has("model_type") || req.dsString("model_type") == "default") &&
            (!req.has("thinking_enabled") || req.dsBool("thinking_enabled") == false) &&
            req.getAsJsonArrayOrNull("ref_file_ids")?.size().let { it == null || it == 0 } &&
            req.get("audio_id").let { it == null || it.isJsonNull } &&
            req.get("action").let { it == null || it.isJsonNull } &&
            (!req.has("preempt") || req.dsBool("preempt") == false))
        if (!supportedRequest) issues += "unsupported_request_protocol"
        val state = StreamState(req?.dsBool("search_enabled"))
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
        return CapturedAnswer(provider = "deepseek", sessionId = exchange.sessionId, exchangeId = exchange.id,
            packageName = exchange.packageName, startedAt = exchange.startedAt, endedAt = exchange.endedAt,
            decoder = id, prompt = prompt, answer = state.answer.toString(), status = status,
            diagnostics = issues.toList(), eventCount = stream.totalEvents)
    }

    private class StreamState(private val searchEnabled: Boolean?) {
        val answer = StringBuilder()
        val issues = linkedSetOf<String>()
        var unknown = false
            private set
        var remoteError = false
            private set
        private var requestId: Long? = null
        private var responseId: Long? = null
        private var initialized = false
        private var textFinished = false
        private var closed = false
        private var path: String? = null
        private var operation: String? = null
        val finished get() = initialized && textFinished && closed

        private fun schema(valid: Boolean, diagnostic: String = "unrecognized_event"): Boolean {
            if (!valid) { unknown = true; issues += diagnostic }
            return valid
        }

        private fun failed() { remoteError = true; issues += "remote_error" }

        fun accept(event: SseEvent) {
            if (!schema(!closed, "event_after_done")) return
            val obj = dsJson(event.data)
            if (event.type.contains("error", ignoreCase = true) || obj?.dsRemoteError() == true) { failed(); return }
            if (!schema(obj != null)) return
            obj!!
            when (event.type) {
                "ready" -> {
                    val reqId = obj.dsLong("request_message_id")
                    val resId = obj.dsLong("response_message_id")
                    if (schema(requestId == null && !initialized && reqId != null && resId != null &&
                            reqId >= 0 && resId >= 0 && reqId != resId && obj.dsString("model_type") == "default")) {
                        requestId = reqId; responseId = resId
                    }
                }
                "message" -> {
                    if (!schema(requestId != null)) return
                    if (!initialized) acceptInitial(obj) else acceptPatch(obj)
                }
                "update_session" -> schema(requestId != null && obj.dsNumber("updated_at"))
                "auto_tts_capability" -> schema(initialized && obj.dsString("status") == "ready")
                "title" -> schema(textFinished && obj.dsString("content") != null)
                "close" -> if (schema(initialized && textFinished && obj.dsString("click_behavior") == "none" &&
                        obj.dsBool("auto_resume") == false)) closed = true
                else -> schema(false)
            }
        }

        private fun acceptInitial(obj: JsonObject) {
            val response = obj.dsObj("v")?.dsObj("response")
            if (response?.dsRemoteError() == true) { failed(); return }
            if (!schema(obj.keySet() == setOf("v") && response != null)) return
            response!!
            if (!schema(response.dsLong("message_id") == responseId && response.dsLong("parent_id") == requestId,
                    "stream_identity_mismatch")) return
            val fragments = response.getAsJsonArrayOrNull("fragments")
            val fragment = fragments?.singleOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            if (!schema(response.dsString("role") == "ASSISTANT" && response.dsString("status") == "WIP" &&
                    response.dsBool("thinking_enabled") == false && response.dsBool("search_enabled") != null &&
                    (searchEnabled == null || response.dsBool("search_enabled") == searchEnabled) &&
                    response.dsBool("search_triggered") == false && response.dsBool("auto_continue") == false &&
                    response.dsBool("has_pending_fragment") == false && response.dsString("conversation_mode") == "DEFAULT" &&
                    response.get("incomplete_message").let { it == null || it.isJsonNull } &&
                    response.getAsJsonArrayOrNull("extra_search_providers")?.size() == 0 &&
                    fragment?.dsString("type") == "RESPONSE" && fragment.dsLong("id") != null &&
                    fragment.dsString("content") != null && fragment.getAsJsonArrayOrNull("references")?.size() == 0,
                    "unsupported_response_protocol")) return
            initialized = true
            answer.append(fragment!!.dsString("content"))
        }

        private fun acceptPatch(obj: JsonObject) {
            if (!schema(!textFinished && obj.keySet().all { it in setOf("p", "o", "v") } && obj.has("v"))) return
            if (obj.has("p")) {
                if (!schema(obj.dsString("p") != null)) return
                path = obj.dsString("p")
            }
            if (obj.has("o")) {
                if (!schema(obj.dsString("o") != null)) return
                operation = obj.dsString("o")
            }
            when {
                path == "response/fragments/-1/content" && operation == "APPEND" ->
                    if (schema(obj.dsString("v") != null)) answer.append(obj.dsString("v"))
                path == "response" && operation == "BATCH" -> {
                    val patches = obj.getAsJsonArrayOrNull("v")
                    if (!schema(patches != null && patches.size() > 0 && patches.all { it.isJsonObject })) return
                    for (item in patches!!) {
                        val patch = item.asJsonObject
                        if (patch.dsRemoteError()) { failed(); continue }
                        if (!schema(patch.keySet() == setOf("p", "v"))) continue
                        when (patch.dsString("p")) {
                            "accumulated_token_usage" -> schema(patch.dsLong("v")?.let { it >= 0 } == true)
                            "quasi_status" -> schema(patch.dsString("v") == "FINISHED")
                            else -> schema(false)
                        }
                    }
                }
                path == "response/status" && operation == "SET" -> {
                    when (obj.dsString("v")) {
                        "FINISHED" -> textFinished = true
                        "ERROR", "FAILED" -> failed()
                        else -> schema(false)
                    }
                }
                else -> schema(false)
            }
        }
    }
}

private fun dsJson(text: String): JsonObject? = runCatching {
    JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
}.getOrNull()
private fun JsonObject.dsObj(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.getAsJsonArrayOrNull(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
private fun JsonObject.dsString(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
private fun JsonObject.dsBool(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
private fun JsonObject.dsLong(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.toString()?.toLongOrNull()
private fun JsonObject.dsNumber(key: String) = get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString.toDoubleOrNull()?.isFinite() == true } == true
private fun JsonObject.dsRemoteError(): Boolean = listOf("code", "error_code").any { key ->
    get(key)?.takeUnless { it.isJsonNull }?.toString()?.let { it !in setOf("0", "\"0\"") } == true
} || listOf("error", "error_msg", "error_message").any { key ->
    get(key)?.takeUnless { it.isJsonNull }?.toString()?.let { it !in setOf("\"\"", "false", "0", "{}") } == true
}
