package com.packetcapture.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.packetcapture.core.*
import java.net.URI

/** Qianwen 7.4.7.3058 quick/v2 text stream, verified against a saved live capture. */
internal class QianwenDecoder : AnswerDecoder {
    override val id = "qianwen-chat-sse/1"

    override fun matches(exchange: HttpExchange): Boolean = runCatching {
        val uri = URI(exchange.url)
        exchange.method == "POST" && uri.scheme == "https" && uri.host == "chat2.qianwen.com" &&
            uri.path == "/api/v1/chat"
    }.getOrDefault(false)

    override fun decode(exchange: HttpExchange, request: BodyPreview, response: BodyPreview): CapturedAnswer {
        val issues = linkedSetOf<String>()
        val req = if (request.binary || request.limited) null else qwJson(request.rawText ?: request.text)
        val messages = req?.qwObjects("messages")
        val message = messages?.singleOrNull()
        val prompt = message?.takeIf { it.qwString("mime_type") == "text/plain" }?.qwString("content")
        if (prompt.isNullOrBlank()) issues += "prompt_unavailable"
        if (messages != null && messages.size != 1) issues += "ambiguous_request_messages"
        val supportedRequest = req == null || messages == null || (req.qwString("protocol_version") == "v2" &&
            req.qwInt("incremental_strategy") == 1 && req.get("json_patch_enabled")?.toString() == "true" &&
            req.qwString("chat_mode") == "quick" && messages?.size == 1 && message?.qwString("mime_type") == "text/plain")
        if (!supportedRequest) issues += "unsupported_request_protocol"
        val reqId = req?.qwString("req_id")?.takeIf { it.isNotBlank() }
        val sessionId = req?.qwString("session_id")?.takeIf { it.isNotBlank() }
        if (reqId == null || sessionId == null) issues += "request_identity_unavailable"
        val state = StreamState(reqId, sessionId)
        val stream = ServerSentEvents.parse(if (response.binary) "" else response.rawText ?: response.text, Int.MAX_VALUE)
        stream.events.forEach { state.accept(it) }
        issues += state.issues
        if (request.binary || response.binary || request.limited || response.limited ||
            listOfNotNull(exchange.requestBody, exchange.responseBody).any { it.truncated || it.observedBytes > it.savedBytes }) {
            issues += "body_unavailable_or_limited"
        }
        if (exchange.completion != Completion.COMPLETE || exchange.endedAt == null ||
            exchange.requestBody == null || exchange.responseBody == null ||
            exchange.requestBody?.inProgress == true || exchange.responseBody?.inProgress == true) issues += "transport_incomplete"
        if (!exchange.error.isNullOrBlank() || exchange.status !in 200..299) issues += "transport_error"
        if (!state.finished) issues += "missing_terminal_marker"
        if (stream.pending) issues += "pending_sse_frame"
        if (state.answer.isBlank()) issues += "empty_answer"
        val status = when {
            state.remoteError || "transport_error" in issues || exchange.completion == Completion.FAILED -> "failed"
            state.unknown || !supportedRequest || "ambiguous_request_messages" in issues -> "unsupported"
            issues.isEmpty() -> "complete"
            else -> "incomplete"
        }
        return CapturedAnswer(provider = "qianwen", sessionId = exchange.sessionId, exchangeId = exchange.id,
            packageName = exchange.packageName, startedAt = exchange.startedAt, endedAt = exchange.endedAt,
            decoder = id, prompt = prompt, answer = state.answer.toString(), status = status,
            searchSources = state.sources.values.toList(), referenceCaptureStatus = if (state.sources.isEmpty()) "not_collected" else "collected",
            diagnostics = issues.toList(), eventCount = stream.totalEvents)
    }

    private class StreamState(private val reqId: String?, private val sessionId: String?) {
        val issues = linkedSetOf<String>()
        val answer = StringBuilder()
        val sources = linkedMapOf<String, SearchSource>()
        var unknown = false
            private set
        var remoteError = false
            private set
        private var nextSequence = 0
        private var textStarted = false
        private var textFinished = false
        private var workflowStarted = false
        private var workflowFinished = false
        private var terminal = false
        private var progressStarted = false
        private var progressFinished = false
        val finished get() = terminal && textStarted && textFinished && (!workflowStarted || workflowFinished) &&
            (!progressStarted || progressFinished)

        private fun schema(valid: Boolean, diagnostic: String = "unrecognized_event"): Boolean {
            if (!valid) { unknown = true; issues += diagnostic }
            return valid
        }

        fun accept(event: SseEvent) {
            if (!schema(!terminal, "event_after_done")) return
            val obj = qwJson(event.data)
            if (!schema(obj != null)) return
            obj!!
            val data = obj.qwObj("data")
            val communication = obj.qwObj("communication")
            val emptyFollowup = data?.qwObjects("messages")?.singleOrNull()?.let {
                it.qwString("mime_type") == "paa/iframe" && emptyFollowup(it)
            } == true
            if (event.type.contains("error", ignoreCase = true) ||
                obj.qwInt("error_code")?.let { it != 0 } == true || !obj.qwString("error_msg").isNullOrEmpty() ||
                data?.qwObj("audit_info")?.qwInt("error_code")?.let { it != 0 } == true ||
                communication?.qwInt("disconnection_signal")?.let { it != 0 } == true) {
                remoteError = true; issues += "remote_error"; return
            }
            if (!schema(obj.qwInt("error_code") == 0 && (obj.qwString("error_msg") == "" || !obj.has("error_msg") && emptyFollowup) &&
                    data?.qwObj("audit_info")?.qwInt("error_code") == 0 && communication?.qwInt("disconnection_signal") == 0)) return
            if (!schema(communication!!.qwString("reqid") != null && communication.qwString("sessionid") != null &&
                    (reqId == null || communication.qwString("reqid") == reqId) &&
                    (sessionId == null || communication.qwString("sessionid") == sessionId), "stream_identity_mismatch")) return
            if (!schema(communication.qwInt("resid") == nextSequence, "stream_sequence_mismatch")) return
            nextSequence++
            val messages = data!!.qwObjects("messages")
            if (!schema(messages != null)) return
            when (event.type) {
                "message" -> {
                    if (!schema(messages!!.size == 1)) return
                    acceptMessage(messages.single())
                }
                "complete" -> {
                    if (schema(messages!!.isEmpty() && data.qwObj("extra_info")?.qwString("sse_end") == "1")) terminal = true
                }
                else -> schema(false)
            }
        }

        private fun acceptMessage(message: JsonObject) {
            val mime = message.qwString("mime_type")
            if (!schema(mime in setOf("signal/post", "bar/workflow", "multi_load/iframe", "bar/progress", "bar/iframe", "paa/iframe"))) return
            val patches = message.qwObjects("patch")
            if (message.has("patch")) {
                if (!schema(!patches.isNullOrEmpty() && mime != "signal/post" && !message.has("content") && !message.has("status"))) return
                for (patch in patches!!) acceptPatch(mime!!, patch)
                return
            }
            when (mime) {
                "bar/progress" -> {
                    val kind = message.qwObj("meta_data")?.qwString("type")
                    when {
                        kind == "cot" && message.qwString("status") == "processing" -> {
                            if (schema(!progressStarted && !textStarted)) progressStarted = true
                        }
                        kind == "generated" && message.qwString("status") == "complete" -> {
                            if (schema(progressStarted && !progressFinished && textFinished)) progressFinished = true
                        }
                        else -> schema(false)
                    }
                }
                "bar/iframe" -> schema(!textStarted && message.qwString("status") == "complete" &&
                    message.qwObj("meta_data")?.qwObjects("sources")?.isEmpty() == true)
                "paa/iframe" -> schema(textFinished && emptyFollowup(message))
                "signal/post" -> schema(!textStarted && message.qwString("status") == "complete" && message.qwObj("meta_data") != null)
                "bar/workflow" -> {
                    val status = message.qwString("status")
                    if (!schema(!workflowFinished && message.qwString("content") == "" &&
                            status in setOf("processing", "complete") && message.qwObj("meta_data") != null)) return
                    workflowStarted = true
                    workflowFinished = status == "complete"
                    acceptLoads(message.qwObj("meta_data")!!, mime)
                }
                "multi_load/iframe" -> {
                    val text = message.qwString("content")
                    if (!schema(!textStarted && message.qwString("status") == "processing" && text != null && message.qwObj("meta_data") != null)) return
                    textStarted = true
                    answer.append(text)
                    acceptLoads(message.qwObj("meta_data")!!, mime)
                }
            }
        }

        /** 仅接受已观察的空追问卡；非空卡及未知结构继续拒绝完成。 */
        private fun emptyFollowup(message: JsonObject): Boolean {
            val meta = message.qwObj("meta_data") ?: return false
            val append = meta.qwObj("append_msg") ?: return false
            fun emptyArray(key: String) = meta.get(key)?.let { it.isJsonArray && it.asJsonArray.size() == 0 } == true
            return message.qwString("status") == "complete" && meta.qwString("show_type") == "" &&
                meta.qwString("scene") == "" && emptyArray("exclusive_card_types") && emptyArray("paas") &&
                append.qwString("mime_type") == "" && append.qwString("content") == "" &&
                append.qwString("status") == "" && append.qwObj("meta_data")?.qwObjects("multi_load")?.isEmpty() == true
        }

        private fun acceptPatch(mime: String, patch: JsonObject) {
            val op = patch.qwString("op")
            val path = patch.qwString("path")
            if (mime == "multi_load/iframe") {
                if (!schema(textStarted && !textFinished)) return
                when {
                    // Despite its JSON-Patch spelling, this protocol's add /content appends.
                    op == "add" && path == "/content" -> if (schema(patch.qwString("value") != null)) answer.append(patch.qwString("value"))
                    op == "remove" && path == "/meta_data/first_packet" -> schema(!patch.has("value"))
                    op == "add" && path == "/meta_data/multi_load/-" -> acceptLoad(patch.qwObj("value"), mime)
                    op == "replace" && path == "/status" -> if (schema(patch.qwString("value") == "complete")) textFinished = true
                    else -> schema(false)
                }
            } else {
                if (!schema(workflowStarted && !workflowFinished)) return
                when {
                    op == "replace" && path == "/meta_data/bar_title" -> schema(patch.qwString("value") != null)
                    op == "add" && path == "/meta_data/multi_load/-" -> acceptLoad(patch.qwObj("value"), mime)
                    else -> schema(false)
                }
            }
        }

        private fun acceptLoads(meta: JsonObject, mime: String) {
            val loads = meta.qwObjects("multi_load")
            if (!schema(loads != null)) return
            // Final workflow snapshots can have an empty array: retain previously collected sources.
            for (load in loads!!) acceptLoad(load, mime)
        }

        private fun acceptLoad(load: JsonObject?, mime: String) {
            if (!schema(load != null)) return
            val content = load!!.qwObj("content")
            when {
                mime == "multi_load/iframe" && load.qwString("type") == "inline_action_reply" ->
                    schema(content?.qwString("text") != null && !load.qwString("source_seq").isNullOrBlank())
                mime == "multi_load/iframe" && load.qwString("type") == "video_note_list" ->
                    // Recommendation cards are metadata; their titles and URLs are not answer text or citations.
                    schema(load.qwString("html") == "" && !load.qwString("source_seq").isNullOrBlank() &&
                        content?.qwString("intro") != null && content.qwObjects("list") != null)
                mime == "bar/workflow" && load.qwString("type") == "bar_ref_source_inline" -> {
                    val docs = content?.qwObjects("docs")
                    if (!schema(docs != null && content.qwString("status") == "complete")) return
                    for (doc in docs!!) {
                        val url = doc.qwString("url")
                        val title = doc.qwString("title")
                        if (!schema(url != null && title != null)) continue
                        val valid = runCatching { URI(url).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
                        if (valid) sources.putIfAbsent(url!!, SearchSource(title!!, url, null))
                    }
                }
                else -> schema(false)
            }
        }
    }
}

private fun qwJson(text: String): JsonObject? = runCatching {
    JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
}.getOrNull()
private fun JsonObject.qwObj(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.qwObjects(key: String): List<JsonObject>? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    ?.takeIf { array -> array.all { it.isJsonObject } }?.map { it.asJsonObject }
private fun JsonObject.qwString(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
private fun JsonObject.qwInt(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
    ?.toString()?.toIntOrNull()
