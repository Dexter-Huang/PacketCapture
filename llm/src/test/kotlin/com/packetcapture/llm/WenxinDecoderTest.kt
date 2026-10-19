package com.packetcapture.llm

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.packetcapture.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures only: no account IDs, real prompts, HAR or credentials. */
class WenxinDecoderTest {
    private val gson = Gson()
    private val decoder = WenxinDecoder()
    private val reqRef = BodyRef("request", 100, 100)
    private val resRef = BodyRef("response", 1000, 1000)
    private fun exchange() = HttpExchange(id = "e1", sessionId = "s1", connectionId = "c1", packageName = "com.baidu.newapp",
        method = "POST", url = "https://yiyanapp.baidu.com/chat/completions", protocol = "HTTP/2",
        startedAt = 100, endedAt = 200, status = 200, completion = Completion.COMPLETE, requestBody = reqRef, responseBody = resRef,
        responseHeaders = listOf(Header("Content-Type", "text/event-stream")))
    private fun request(prompt: String = "合成问题", new: Boolean = false) = gson.toJson(mapOf(
        "content" to prompt, "contentType" to "text", "inputSource" to "user_input", "querySource" to "user_input",
        "variantMode" to "conversation", "isTemporaryChat" to 0, "mainAssistant" to 0, "plugins" to emptyMap<String, Any>(),
        "models" to mapOf("select" to "auto", "dispatch" to "auto", "normal" to true, "replyMode" to "normal"),
        "context" to if (new) emptyList() else listOf("old-message"), "newBot" to new, "characterId" to "character",
        "botId" to if (new) "" else "bot", "sessionId" to if (new) "" else "session", "parentId" to if (new) "" else "prior"))
    private fun frame(data: Map<String, Any?>) = "data: ${gson.toJson(mapOf("errno" to 0, "errmsg" to "success", "data" to data))}\n\n"
    private fun initial() = frame(mapOf("type" to 1, "data" to mapOf(
        "isDeep" to 0, "isDepthAnalysis" to 0, "mcpTool" to "", "sessionDel" to 0, "botType" to 7,
        "botId" to "bot", "sessionId" to "session", "characterId" to "character", "promptId" to "prompt",
        "messageInfos" to mapOf("ask" to mapOf("messageId" to "ask", "parentId" to "prior"),
            "answer" to mapOf("messageId" to "reply", "parentId" to "ask")))))
    private fun generator(component: String, data: Map<String, Any?>) = mapOf(
        "meta" to mapOf("workflow" to "orion", "isIntervene" to false),
        "data" to mapOf("antiFlag" to 0, "needClearHistory" to false, "isSafe" to 1, "isFinished" to false,
            "component" to component, "data" to data))
    private fun chunk(index: Int, text: String? = null, finish: Boolean = false, component: String = "markdown-yiyan",
        componentData: Map<String, Any?>? = null, references: List<Map<String, Any?>>? = null): String {
        val data = linkedMapOf<String, Any?>("botId" to "bot", "sessionId" to "session", "characterId" to "character", "promptId" to "prompt",
            "messageId" to "reply", "parentId" to "ask", "chunkIndex" to index, "content" to "", "endpoint" to "",
            "contentType" to "aigc", "askType" to "text", "finish" to if (finish) 1 else 0, "replace" to "", "flag" to 0,
            "isSafe" to 1, "needClearHistory" to 0)
        if (text != null || componentData != null) data["generator"] = generator(component, componentData ?: mapOf("value" to text, "theme" to emptyMap<String, Any>()))
        if (references != null) data["reference"] = mapOf("searchInfo" to references)
        return frame(mapOf("type" to 2, "data" to data))
    }
    private fun source(url: String = "https://example.test/article", index: Int = 1, title: String = "合成来源") =
        mapOf<String, Any?>("type" to "", "title" to title, "url" to url, "index" to index)
    private fun stream() = initial() + chunk(0, "开头🌈\n") + chunk(1, "**正文**") + chunk(2, finish = true, references = listOf(source()))
    private fun decode(body: String = stream(), req: String = request(), e: HttpExchange = exchange(),
        rp: BodyPreview = BodyPreview(req, false, false), sp: BodyPreview = BodyPreview(body, false, false)) = decoder.decode(e, rp, sp)
    private fun edit(text: String, transform: (JsonObject) -> Unit): String {
        val obj = JsonParser.parseString(text.removePrefix("data: ").trim()).asJsonObject
        transform(obj)
        return if (text.startsWith("data: ")) "data: ${gson.toJson(obj)}\n\n" else gson.toJson(obj)
    }
    private fun chunkData(obj: JsonObject) = obj.getAsJsonObject("data").getAsJsonObject("data")

    @Test fun reconstructsDeltaTextAndIndexedReferencesWithoutChangingRawMarkdown() {
        val a = decode()
        assertEquals("complete", a.status)
        assertEquals("wenxin-chat-sse/1", a.decoder)
        assertEquals("合成问题", a.prompt)
        assertEquals("开头🌈\n**正文**", a.answer)
        assertEquals(4, a.eventCount)
        assertEquals(listOf(SearchSource("合成来源", "https://example.test/article", 1)), a.searchSources)
        assertEquals("collected", a.referenceCaptureStatus)
        assertEquals("", a.reasoning)
        assertTrue(a.diagnostics.isEmpty())
    }

    @Test fun exactEndpointExcludesHistoryOtherOriginsAndNonstandardPorts() {
        assertTrue(decoder.matches(exchange().copy(url = exchange().url + "?synthetic=1")))
        for (url in listOf("http://yiyanapp.baidu.com/chat/completions", "https://yiyanapp.baidu.com/chat/history",
            "https://yiyanapp.baidu.com/chat/completions/", "https://yiyanapp.baidu.com.example.test/chat/completions",
            "https://user@yiyanapp.baidu.com/chat/completions", "https://yiyanapp.baidu.com:8443/chat/completions",
            "https://yiyanapp.baidu.com/chat/completions#fragment")) assertFalse(url, decoder.matches(exchange().copy(url = url)))
        assertFalse(decoder.matches(exchange().copy(method = "GET")))
    }

    @Test fun newSessionUsesServerAssignedIdentityAndContinuationChecksRequestIdentity() {
        assertEquals("complete", decode(req = request(new = true)).status)
        assertEquals("unsupported", decode(req = request().replace("\"sessionId\":\"session\"", "\"sessionId\":\"other\"")).status)
        assertEquals("unsupported", decode(req = request().replace("\"botId\":\"bot\"", "\"botId\":\"other\"")).status)
        assertEquals("unsupported", decode(req = request().replace("\"parentId\":\"prior\"", "\"parentId\":\"other\"")).status)
    }

    @Test fun zeroPromptIdIsAValidOpaqueIdentity() {
        assertEquals("complete", decode(stream().replace("\"promptId\":\"prompt\"", "\"promptId\":\"0\"")).status)
    }

    @Test fun malformedRequestIdentityCannotAuthorizeAnOtherwiseValidStream() {
        assertNotEquals("complete", decode(req = edit(request()) { it.addProperty("newBot", true) }).status)
        assertNotEquals("complete", decode(req = edit(request(new = true)) { it.addProperty("newBot", false) }).status)
        assertNotEquals("complete", decode(req = edit(request(new = true)) { it.remove("parentId") }).status)
    }

    @Test fun preservesExactPromptAndRejectsUnavailableEvidence() {
        assertEquals(" 合成\n🌈 ", decode(req = request(" 合成\n🌈 ")).prompt)
        for (req in listOf("not-json", "{}", request(" "), edit(request()) { it.remove("newBot") }))
            assertNotEquals("complete", decode(req = req).status)
        assertEquals("incomplete", decode(rp = BodyPreview(request(), true, false)).status)
    }

    @Test fun rejectsUnverifiedRequestModesAndAttachments() {
        for (change in listOf<(JsonObject) -> Unit>(
            { it.addProperty("contentType", "image") }, { it.addProperty("variantMode", "future") },
            { it.addProperty("inputSource", "voice") }, { it.addProperty("isTemporaryChat", 1) },
            { it.getAsJsonObject("models").addProperty("normal", false) },
            { it.getAsJsonObject("models").addProperty("replyMode", "deep") },
            { it.getAsJsonObject("plugins").addProperty("future", true) },
            { it.add("chooses", gson.toJsonTree(listOf("file"))) },
            { it.add("context", gson.toJsonTree(listOf(mapOf("image" to "file")))) }))
            assertEquals("unsupported", decode(req = edit(request(), change)).status)
    }

    @Test fun validatesInitialAndChunkIdentities() {
        for (field in listOf("botId", "sessionId", "characterId", "promptId", "messageId", "parentId")) {
            val bad = edit(chunk(0, "text")) { chunkData(it).addProperty(field, "other") }
            assertEquals(field, "unsupported", decode(initial() + bad + chunk(1, finish = true)).status)
        }
        assertEquals("unsupported", decode(chunk(0, "text") + chunk(1, finish = true)).status)
        assertEquals("unsupported", decode(initial() + stream()).status)
        val bad = edit(initial()) { chunkData(it).getAsJsonObject("messageInfos").getAsJsonObject("answer").addProperty("parentId", "other") }
        assertEquals("unsupported", decode(bad + chunk(0, "text") + chunk(1, finish = true)).status)
    }

    @Test fun rejectsDuplicateMissingAndReorderedChunkIndices() {
        for (body in listOf(initial() + chunk(1, "text") + chunk(2, finish = true),
            initial() + chunk(0, "a") + chunk(0, "a") + chunk(1, finish = true),
            initial() + chunk(0, "a") + chunk(2, "b") + chunk(1, finish = true)))
            assertEquals("unsupported", decode(body).status)
    }

    @Test fun requiresTerminalFrameAndRejectsLateEvents() {
        assertEquals("incomplete", decode(initial() + chunk(0, "text")).status)
        assertEquals("incomplete", decode(initial() + chunk(0, finish = true)).status)
        assertEquals("unsupported", decode(stream() + chunk(3, "late")).status)
        assertEquals("incomplete", decode(stream().trimEnd()).status)
        assertEquals("incomplete", decode(stream() + "data: {").status)
    }

    @Test fun ignoresOnlyKnownProcessAndInputComponentsAndDoesNotTreatThemAsAnswer() {
        val thinking = chunk(0, component = "thinkingSteps", componentData = mapOf("noReasoning" to true,
            "reasoningContentArr" to listOf(""), "title" to "process-not-answer"))
        val input = chunk(2, component = "input", componentData = mapOf("guideWord" to "not-answer", "isDefaultPlaceholder" to ""))
        val a = decode(initial() + thinking + chunk(1, "answer") + input + chunk(3, finish = true))
        assertEquals("complete", a.status)
        assertEquals("answer", a.answer)
        for (bad in listOf(chunk(0, component = "future", componentData = emptyMap()),
            chunk(0, component = "thinkingSteps", componentData = mapOf("noReasoning" to false, "reasoningContentArr" to listOf("private thought"))),
            chunk(0, component = "thinkingSteps", componentData = mapOf("noReasoning" to true, "reasoningContentArr" to listOf("nonempty")))))
            assertEquals("unsupported", decode(initial() + bad + chunk(1, "answer") + chunk(2, finish = true)).status)
    }

    @Test fun rejectsReplacementContentOtherProtocolsAndMalformedEvents() {
        for (field in listOf("replace", "content", "endpoint", "contentType", "askType")) {
            val bad = edit(chunk(0, "text")) { chunkData(it).addProperty(field, "future") }
            assertEquals("unsupported", decode(initial() + bad + chunk(1, finish = true)).status)
        }
        for (bad in listOf("data: not-json\n\n", "data: null\n\n", "event: future\ndata: {}\n\n",
            edit(chunk(0, "text")) { it.getAsJsonObject("data").addProperty("type", 99) },
            edit(chunk(0, "text")) { chunkData(it).addProperty("chunkIndex", "0") },
            edit(chunk(0, "text")) { chunkData(it).add("generator", gson.toJsonTree("bad")) }))
            assertEquals("unsupported", decode(initial() + bad + chunk(1, finish = true)).status)
    }

    @Test fun remoteAndSafetyErrorsOverrideEndMarkersAndHttp200() {
        for (bad in listOf("event: error\ndata: {}\n\n", "data: {\"errno\":7,\"errmsg\":\"synthetic\"}\n\n",
            edit(chunk(0, "text")) { chunkData(it).addProperty("isSafe", 0) },
            edit(chunk(0, "text")) { chunkData(it).getAsJsonObject("generator").getAsJsonObject("data").addProperty("antiFlag", 1) }))
            assertEquals("failed", decode(initial() + bad + chunk(1, finish = true)).status)
        assertEquals("failed", decode(stream() + "data: {\"errno\":7}\n\n").status)
    }

    @Test fun filtersUnsafeUrlsDeduplicatesSourcesAndPreservesExplicitIndices() {
        val refs = listOf(source(), source(index = 2, title = "duplicate"), source("javascript:alert(1)", 3),
            source("https://user:pass@example.test/private", 4), source("https://second.test/a", 8))
        val a = decode(initial() + chunk(0, "text", finish = true, references = refs))
        assertEquals("complete", a.status)
        assertEquals(listOf(1, 8), a.searchSources.map { it.index })
        assertEquals(listOf("https://example.test/article", "https://second.test/a"), a.searchSources.map { it.url })
        assertEquals("not_collected", decode(initial() + chunk(0, "text", finish = true)).referenceCaptureStatus)
        val bad = edit(chunk(0, "text", finish = true, references = listOf(source()))) {
            chunkData(it).getAsJsonObject("reference").getAsJsonArray("searchInfo")[0].asJsonObject.addProperty("index", "1")
        }
        assertEquals("unsupported", decode(initial() + bad).status)
    }

    @Test fun transportCancellationNeverBecomesCompleteEvenWhenProtocolIsFinished() {
        val a = decode(e = exchange().copy(completion = Completion.FAILED, error = "连接提前结束", responseBody = resRef.copy(truncated = true)))
        assertEquals("failed", a.status)
        assertEquals("开头🌈\n**正文**", a.answer)
        assertTrue(a.diagnostics.containsAll(listOf("transport_incomplete", "transport_error", "body_unavailable_or_limited")))
        assertFalse(a.diagnostics.contains("missing_terminal_marker"))
        for (e in listOf(exchange().copy(completion = Completion.ACTIVE), exchange().copy(completion = Completion.INTERRUPTED),
            exchange().copy(endedAt = null), exchange().copy(requestBody = null), exchange().copy(responseBody = null),
            exchange().copy(responseBody = resRef.copy(inProgress = true)), exchange().copy(requestBody = reqRef.copy(truncated = true)),
            exchange().copy(responseBody = resRef.copy(observedBytes = 1001)))) assertEquals("incomplete", decode(e = e).status)
        assertEquals("failed", decode(e = exchange().copy(status = 503)).status)
        assertEquals("incomplete", decode(sp = BodyPreview(stream(), false, true)).status)
    }

    @Test fun readsRawTextAndMoreEventsThanUiWindow() {
        val body = initial() + (0..600).joinToString("") { chunk(it, "$it,") } + chunk(601, finish = true)
        val a = decode(rp = BodyPreview("preview", false, false, rawText = request()), sp = BodyPreview("preview", false, false, rawText = body))
        assertEquals("complete", a.status)
        assertEquals(603, a.eventCount)
        assertTrue(a.answer.endsWith("599,600,"))
        assertEquals("complete", decode(stream().replace("\n", "\r\n")).status)
    }

    @Test fun readerReturnsRepeatedPromptsAsAmbiguousAndExportsNoCredentialHeaders() = runTest {
        val e = exchange().copy(packageName = null, requestHeaders = listOf(Header("Authorization", "synthetic-secret")))
        val repo = object : CaptureRepository {
            override suspend fun session(id: String) = CaptureSession(id = "s1", title = "synthetic", packages = listOf("com.baidu.newapp"))
            override suspend fun allExchanges(sessionId: String) = listOf(e, e.copy(id = "e2", startedAt = 300))
            override suspend fun allConnections(sessionId: String) = emptyList<ConnectionRecord>()
            override fun sessions(query: String): Flow<List<CaptureSession>> = error("unused")
            override fun exchanges(sessionId: String?, filter: ExchangeFilter, limit: Int): Flow<List<HttpExchange>> = error("UI window forbidden")
            override fun connections(sessionId: String?) = flowOf(emptyList<ConnectionRecord>())
            override fun exchange(id: String): Flow<HttpExchange?> = error("unused")
            override suspend fun connection(id: String): ConnectionRecord? = error("unused")
            override suspend fun saveSession(session: CaptureSession) = error("unused")
            override suspend fun saveConnection(connection: ConnectionRecord) = error("unused")
            override suspend fun saveExchange(exchange: HttpExchange) = error("unused")
            override suspend fun deleteSession(id: String) = error("unused")
            override suspend fun clearStorage() = error("unused")
            override suspend fun recoverInterrupted() = error("unused")
        }
        val bodies = object : BodyStore {
            override suspend fun preview(ref: BodyRef?, headers: List<Header>) = BodyPreview(if (ref?.key == "request") request() else stream(), false, false)
            override fun open(sessionId: String, exchangeId: String, part: BodyPart, limit: Long, totalLimit: Long): BodySink = error("unused")
            override suspend fun read(ref: BodyRef, maxBytes: Int): ByteArray = error("unused")
            override suspend fun deleteSession(sessionId: String) = error("unused")
            override suspend fun clearAll() = error("unused")
            override suspend fun usedBytes(): Long = error("unused")
            override suspend fun recoverOrphans(liveSessionIds: Set<String>) = error("unused")
        }
        val reader = AnswerReader(repo, bodies)
        val selector = AnswerSelector(provider = "wenxin", prompt = "合成问题")
        assertEquals("ambiguous", reader.read("s1", selector).selectionStatus)
        val batch = reader.read("s1", selector.copy(exchangeId = "e2", startedAfter = 300))
        assertEquals("unique_complete", batch.selectionStatus)
        assertEquals("wenxin-chat-sse/1", batch.results.single().decoder)
        assertEquals("none", reader.read("s1", selector.copy(prompt = "合成问题 ")).selectionStatus)
        assertEquals("none", reader.read("s1", selector.copy(provider = "deepseek")).selectionStatus)
        assertFalse(AnswerJson.encode(batch).contains("synthetic-secret"))
        assertEquals("verified_sample", PlatformCatalog.forPackage("com.baidu.newapp")?.support)
    }
}
