package com.packetcapture.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.packetcapture.core.*
import java.net.URI

/** Doubao 15.0.0 Android /chat/completion, verified from a live plaintext capture. */
internal class DoubaoDecoder : AnswerDecoder {
    override val id = "doubao-chat-sse/2"

    override fun matches(exchange: HttpExchange): Boolean = runCatching {
        val uri = URI(exchange.url)
        exchange.method == "POST" && uri.scheme == "https" &&
            uri.host == "api5-normal-gl.doubao.com" && uri.path == "/chat/completion"
    }.getOrDefault(false)

    override fun decode(exchange: HttpExchange, request: BodyPreview, response: BodyPreview): CapturedAnswer {
        val issues = linkedSetOf<String>()
        val encryptionFlag = (exchange.requestHeaders + exchange.responseHeaders).any {
            it.name.equals("x-tt-e-b", ignoreCase = true) && it.value.trim() == "1"
        }
        val requestJson = if (request.binary || request.limited) null else json(request.rawText ?: request.text)
        // Conversation history lives in option.history_messages, not in the current messages array.
        val messages = requestJson?.objects("messages")
        val message = messages?.singleOrNull()
        val blocks = message?.objects("content_block")
        val prompt = blocks?.takeIf { it.isNotEmpty() && it.all { block ->
            block.int("block_type") == 10000 && block.obj("content")?.obj("text_block")?.string("text") != null
        } }?.joinToString("") { it.obj("content")!!.obj("text_block")!!.string("text")!! }
        if (prompt.isNullOrBlank()) issues += "prompt_unavailable"
        if (messages != null && messages.size != 1) issues += "ambiguous_request_messages"
        val state = StreamState(message?.string("local_message_id"), prompt)
        val responseText = response.rawText ?: response.text
        val stream = ServerSentEvents.parse(if (response.binary) "" else responseText, Int.MAX_VALUE)
        // The successful plaintext sample still has x-tt-e-b: 1 on the response.
        // The flag alone is not evidence of ciphertext; inspect the actual entity too.
        val encrypted = encryptionFlag && (request.binary || response.binary ||
            (stream.totalEvents == 0 && !response.limited && responseText.isNotBlank()))
        if (!encrypted) stream.events.forEach { state.accept(it) }
        issues += state.issues
        if (encrypted) issues += "application_payload_encrypted"
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
            state.unknown || encrypted || "ambiguous_request_messages" in issues -> "unsupported"
            issues.isEmpty() -> "complete"
            else -> "incomplete"
        }
        return CapturedAnswer(provider = "doubao", sessionId = exchange.sessionId, exchangeId = exchange.id,
            packageName = exchange.packageName, startedAt = exchange.startedAt, endedAt = exchange.endedAt,
            decoder = id, prompt = prompt, answer = state.answer.toString(), status = status,
            searchSources = state.sources.values.toList(),
            referenceCaptureStatus = if (state.sources.isEmpty()) "not_collected" else "collected",
            diagnostics = issues.toList(), eventCount = stream.totalEvents)
    }

    private class StreamState(private val localMessageId: String?, private val prompt: String?) {
        val issues = linkedSetOf<String>()
        val answer = StringBuilder()
        val sources = linkedMapOf<String, SearchSource>()
        var unknown = false
            private set
        var remoteError = false
            private set
        private var acknowledged = false
        private var messageId: String? = null
        private var blockId: String? = null
        private var textFinished = false
        private val loading = linkedMapOf<String, Boolean>()
        private var searchTransition = false
        private var endStage = 0
        val finished get() = acknowledged && messageId != null && textFinished && endStage == 3 && loading.isEmpty()

        private fun requireSchema(condition: Boolean): Boolean {
            if (!condition) { unknown = true; issues += "unrecognized_event" }
            return condition
        }

        fun accept(event: SseEvent) {
            if (endStage == 3) { unknown = true; issues += "event_after_done"; return }
            val obj = json(event.data)
            if (!requireSchema(obj != null)) return
            obj!!
            if (event.type.contains("ERROR", ignoreCase = true) || obj.hasRemoteError()) {
                remoteError = true; issues += "remote_error"; return
            }
            when (event.type) {
                "SSE_HEARTBEAT" -> requireSchema(obj.size() == 0)
                "SSE_ACK" -> {
                    val query = obj.objects("query_list")?.singleOrNull()
                    val acknowledgedId = query?.string("local_message_id")
                    if (requireSchema(!acknowledged && endStage == 0 && !acknowledgedId.isNullOrBlank() &&
                            (localMessageId == null || acknowledgedId == localMessageId))) acknowledged = true
                }
                "FULL_MSG_NOTIFY" -> {
                    val message = obj.obj("message")
                    val echoBlocks = message?.objects("content_block")
                    val echo = echoBlocks?.takeIf { it.isNotEmpty() && it.all { b ->
                        b.int("block_type") == 10000 && b.obj("content")?.obj("text_block")?.string("text") != null
                    } }?.joinToString("") { it.obj("content")!!.obj("text_block")!!.string("text")!! }
                    // This is the server's user-message echo; never append it to the answer.
                    requireSchema(acknowledged && endStage == 0 && message?.int("user_type") == 1 &&
                        (localMessageId == null || message.string("local_message_id") == localMessageId) &&
                        echo != null && (prompt == null || echo == prompt))
                }
                "STREAM_MSG_NOTIFY" -> {
                    val meta = obj.obj("meta")
                    val id = meta?.string("message_id")
                    if (requireSchema(acknowledged && messageId == null && endStage == 0 &&
                            meta?.int("user_type") == 2 && !id.isNullOrBlank())) {
                        messageId = id
                        acceptBlocks(obj.obj("content")?.objects("content_block"))
                    }
                }
                "CHUNK_DELTA" -> {
                    val delta = obj.string("text")
                    if (requireSchema(messageId != null && blockId != null && !textFinished && endStage == 0 && delta != null)) {
                        answer.append(delta)
                    }
                }
                "STREAM_CHUNK" -> {
                    if (!requireSchema(messageId != null && obj.string("message_id") == messageId && endStage <= 2)) return
                    val patches = obj.objects("patch_op")
                    if (!requireSchema(!patches.isNullOrEmpty())) return
                    for (patch in patches!!) {
                        val value = patch.obj("patch_value")
                        if (!requireSchema(value != null)) continue
                        when (patch.int("patch_object")) {
                            1 -> if (requireSchema(endStage == 0)) {
                                when (patch.int("patch_type")) {
                                    1 -> acceptBlocks(value!!.objects("content_block"))
                                    3 -> removeLoading(value!!.objects("content_block"))
                                    else -> requireSchema(false)
                                }
                            }
                            3 -> requireSchema(patch.int("patch_type") == 2 &&
                                (value!!.size() == 0 && textFinished ||
                                    value.keySet() == setOf("content_status") && value.int("content_status") == 100 &&
                                    searchTransition && blockId == null && endStage == 0))
                            50 -> requireSchema(patch.int("patch_type") == 1 &&
                                (value!!.size() == 0 || value.obj("ext") != null))
                            else -> requireSchema(false)
                        }
                    }
                }
                "SSE_REPLY_END" -> {
                    val stage = obj.int("end_type")
                    if (!requireSchema(stage == endStage + 1 && messageId != null && textFinished && loading.isEmpty())) return
                    when (stage) {
                        1 -> {
                            val attr = obj.obj("msg_finish_attr")
                            if (!requireSchema(attr?.string("msgid") == messageId)) return
                            if (attr!!.hasRemoteError()) { remoteError = true; issues += "remote_error" }
                        }
                        2 -> {
                            val attr = obj.obj("answer_finish_attr")
                            if (!requireSchema(attr != null)) return
                            if (attr!!.hasRemoteError()) { remoteError = true; issues += "remote_error" }
                        }
                        3 -> Unit
                        else -> { requireSchema(false); return }
                    }
                    endStage = stage!!
                }
                else -> requireSchema(false)
            }
        }

        private fun removeLoading(blocks: List<JsonObject>?) {
            if (!requireSchema(!blocks.isNullOrEmpty() && blockId == null)) return
            for (block in blocks!!) {
                val id = block.string("block_id")
                if (requireSchema(block.int("block_type") == 10101 && block.int("patch_type") == 2 &&
                        block.bool("is_finish") == false && block.obj("content")?.size() == 0 && loading[id] == true)) {
                    loading.remove(id)
                    searchTransition = true
                }
            }
        }

        private fun acceptSearch(block: JsonObject) {
            val search = block.obj("content")?.obj("search_query_result_block")
            val results = search?.objects("results")
            if (!requireSchema(block.int("patch_type") == 2 && block.bool("is_finish") == true &&
                    search?.int("scene") == 2 && search.int("search_type") == 1 && results != null &&
                    searchTransition && loading.isEmpty() && blockId == null)) return
            for (result in results!!) {
                val card = when (result.keySet()) {
                    setOf("text_card") -> result.obj("text_card")
                    setOf("video_card") -> result.obj("video_card")
                    else -> null
                }
                if (!requireSchema(card != null)) continue
                val video = result.has("video_card")
                val url = card!!.string(if (video) "main_site_url" else "url") ?: continue
                val valid = runCatching { URI(url).let { it.scheme in setOf("http", "https") && !it.host.isNullOrEmpty() } }.getOrDefault(false)
                if (valid && url !in sources) sources[url] = SearchSource(
                    card.string(if (video) "video_captions" else "title").orEmpty(), url, card.int("index"))
            }
        }

        private fun acceptBlocks(blocks: List<JsonObject>?) {
            if (!requireSchema(!blocks.isNullOrEmpty())) return
            for (block in blocks!!) {
                when (block.int("block_type")) {
                    10101 -> {
                        val id = block.string("block_id")
                        val value = block.obj("content")?.obj("loading_block")
                        if (requireSchema(!id.isNullOrBlank() && block.int("patch_type") == 2 &&
                                block.bool("is_finish") != null && value?.int("scene") == 2 &&
                                value.obj("text_loading") != null && blockId == null && loading[id] != true)) {
                            loading[id!!] = block.bool("is_finish") == true
                        }
                    }
                    10025 -> acceptSearch(block)
                    10000 -> {
                        val id = block.string("block_id")
                        val text = block.obj("content")?.obj("text_block")
                        if (!requireSchema(!id.isNullOrBlank() && (blockId == null || blockId == id) &&
                                !textFinished && block.int("patch_type") == 1 && text != null &&
                                (!text.has("text") || text.string("text") != null) && block.bool("is_finish") != null)) continue
                        blockId = id
                        answer.append(text!!.string("text").orEmpty())
                        textFinished = block.bool("is_finish") == true
                    }
                    10050 -> requireSchema(block.int("patch_type") == 2 && block.bool("is_finish") == true &&
                        block.obj("content")?.obj("rich_media_block") != null)
                    else -> requireSchema(false)
                }
            }
        }
    }
}

private fun json(text: String): JsonObject? = runCatching {
    JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
}.getOrNull()
private fun JsonObject.obj(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.objects(key: String): List<JsonObject>? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    ?.takeIf { array -> array.all { it.isJsonObject } }?.map { it.asJsonObject }
private fun JsonObject.string(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
private fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
    ?.toString()?.toIntOrNull()
private fun JsonObject.bool(key: String): Boolean? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
private fun JsonObject.hasRemoteError(): Boolean = listOf("code", "error_code").any { key ->
    get(key)?.takeUnless { it.isJsonNull }?.let { it.toString() != "0" && it.toString() != "\"0\"" } == true
} || listOf("error", "error_msg", "error_message").any { key ->
    get(key)?.takeUnless { it.isJsonNull }?.let { it != JsonObject() && it.toString() !in listOf("\"\"", "false", "0") } == true
}
