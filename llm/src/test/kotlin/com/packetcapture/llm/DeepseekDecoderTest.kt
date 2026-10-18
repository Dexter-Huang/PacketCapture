package com.packetcapture.llm

import com.google.gson.Gson
import com.packetcapture.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Entirely synthetic messages; real HAR and account data remain outside the test source set. */
class DeepseekDecoderTest {
    private val gson = Gson()
    private val decoder = DeepseekDecoder()
    private val reqRef = BodyRef("request", observedBytes = 100, savedBytes = 100)
    private val resRef = BodyRef("response", observedBytes = 1000, savedBytes = 1000)
    private fun exchange() = HttpExchange(id = "e1", sessionId = "s1", connectionId = "c1",
        packageName = "com.deepseek.chat", method = "POST", url = "https://chat.deepseek.com/api/v0/chat/completion",
        protocol = "HTTP/2", startedAt = 100, endedAt = 200, status = 200, completion = Completion.COMPLETE,
        requestBody = reqRef, responseBody = resRef, responseHeaders = listOf(Header("Content-Type", "text/event-stream")))
    private fun request(prompt: String = "合成问题") = """{"chat_session_id":"synthetic-session","parent_message_id":null,"prompt":${gson.toJson(prompt)},"ref_file_ids":[],"thinking_enabled":false,"search_enabled":true,"audio_id":null,"preempt":false,"model_type":"default","action":null}"""
    private fun frame(data: String, event: String = "message") = "event: $event\ndata: $data\n\n"
    private val ready get() = frame("""{"request_message_id":11,"response_message_id":12,"model_type":"default"}""", "ready")
    private fun initial(text: String = "开头") = frame("""{"v":{"response":{"message_id":12,"parent_id":11,"role":"ASSISTANT","status":"WIP","thinking_enabled":false,"search_enabled":true,"search_triggered":false,"auto_continue":false,"has_pending_fragment":false,"conversation_mode":"DEFAULT","incomplete_message":null,"extra_search_providers":[],"fragments":[{"id":7,"type":"RESPONSE","content":${gson.toJson(text)},"references":[],"stage_id":1}]}}}""")
    private fun append(text: String) = frame("""{"p":"response/fragments/-1/content","o":"APPEND","v":${gson.toJson(text)}}""")
    private fun inherited(text: String) = frame("""{"v":${gson.toJson(text)}}""")
    private val batch get() = frame("""{"p":"response","o":"BATCH","v":[{"p":"accumulated_token_usage","v":123},{"p":"quasi_status","v":"FINISHED"}]}""")
    private val finish get() = frame("""{"p":"response/status","o":"SET","v":"FINISHED"}""")
    private val close get() = frame("""{"click_behavior":"none","auto_resume":false}""", "close")
    private fun stream() = ready + frame("""{"updated_at":123.5}""", "update_session") + initial() + append("🌈\n") +
        inherited("**正文**") + batch + frame("""{"status":"ready"}""", "auto_tts_capability") + finish +
        frame("""{"content":"标题不是答案"}""", "title") + close
    private fun decode(body: String = stream(), req: String = request(), e: HttpExchange = exchange(),
        rp: BodyPreview = BodyPreview(req, false, false), sp: BodyPreview = BodyPreview(body, false, false)) = decoder.decode(e, rp, sp)

    @Test fun initialTextInheritedAppendUnicodeAndMetadataProduceOneAnswer() {
        val result = decode()
        assertEquals("complete", result.status)
        assertEquals("开头🌈\n**正文**", result.answer)
        assertEquals("合成问题", result.prompt)
        assertEquals("deepseek-chat-sse/1", result.decoder)
        assertEquals(10, result.eventCount)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals("", result.reasoning)
        assertTrue(result.searchSources.isEmpty())
        assertEquals("not_collected", result.referenceCaptureStatus)
    }

    @Test fun restrictsEndpointIdentityAndDoesNotTreatHistoryAsACompletion() {
        assertTrue(decoder.matches(exchange()))
        assertTrue(decoder.matches(exchange().copy(url = exchange().url + "?synthetic=1")))
        for (url in listOf("https://chat.deepseek.com/api/v0/chat/history_messages", "https://chat.deepseek.com/api/v0/chat/completion/",
            "http://chat.deepseek.com/api/v0/chat/completion", "https://chat.deepseek.com.example.test/api/v0/chat/completion",
            "https://user@chat.deepseek.com/api/v0/chat/completion", "https://chat.deepseek.com:8443/api/v0/chat/completion"))
            assertFalse(url, decoder.matches(exchange().copy(url = url)))
        assertFalse(decoder.matches(exchange().copy(method = "GET")))
    }

    @Test fun preservesExactPromptAndRejectsMissingRequestEvidence() {
        assertEquals(" 合成\n🌈 ", decode(req = request(" 合成\n🌈 ")).prompt)
        for (req in listOf("{}", "not-json", request(" "), request().replace("\"chat_session_id\":\"synthetic-session\",", ""),
            request().replace("\"search_enabled\":true,", ""), request().replace("\"ref_file_ids\":[]", "\"ref_file_ids\":{}")))
            assertEquals(req, "incomplete", decode(req = req).status)
    }

    @Test fun unsupportedRequestModesAndAttachmentsNeverClaimSuccess() {
        for ((old, new) in listOf("\"thinking_enabled\":false" to "\"thinking_enabled\":true", "\"model_type\":\"default\"" to "\"model_type\":\"future\"",
            "\"ref_file_ids\":[]" to "\"ref_file_ids\":[\"file\"]", "\"audio_id\":null" to "\"audio_id\":\"audio\"",
            "\"action\":null" to "\"action\":\"continue\"", "\"preempt\":false" to "\"preempt\":true"))
            assertEquals(new, "unsupported", decode(req = request().replace(old, new)).status)
    }

    @Test fun requiresReadyAndMatchingDistinctMessageIds() {
        for (s in listOf(stream().removePrefix(ready), ready + stream(), stream().replace("\"parent_id\":11", "\"parent_id\":99"),
            stream().replace("\"message_id\":12", "\"message_id\":99"), stream().replace("\"response_message_id\":12", "\"response_message_id\":11"),
            stream().replace("\"message_id\":12", "\"message_id\":\"12\""), stream().replace("\"model_type\":\"default\"", "\"model_type\":\"future\"")))
            assertEquals("unsupported", decode(s).status)
    }

    @Test fun searchReasoningMultipleFragmentsAndIncompleteResponsesAreUnsupported() {
        for ((old, new) in listOf("\"search_triggered\":false" to "\"search_triggered\":true", "\"references\":[]" to "\"references\":[{\"url\":\"https://example.test\"}]",
            "\"type\":\"RESPONSE\"" to "\"type\":\"THINK\"", "\"role\":\"ASSISTANT\"" to "\"role\":\"USER\"",
            "\"auto_continue\":false" to "\"auto_continue\":true", "\"has_pending_fragment\":false" to "\"has_pending_fragment\":true",
            "\"extra_search_providers\":[]" to "\"extra_search_providers\":[\"future\"]", "\"incomplete_message\":null" to "\"incomplete_message\":\"blocked\"",
            "\"stage_id\":1}" to "\"stage_id\":1},{\"id\":8,\"type\":\"RESPONSE\",\"content\":\"other\",\"references\":[]}"))
            assertEquals(new, "unsupported", decode(stream().replace(old, new)).status)
    }

    @Test fun bothFinishedStatusAndNonResumingCloseAreRequired() {
        assertEquals("incomplete", decode(stream().removeSuffix(close)).status)
        assertEquals("unsupported", decode(stream().replace(finish, "")).status)
        assertEquals("unsupported", decode(stream().replace("\"auto_resume\":false", "\"auto_resume\":true")).status)
        assertEquals("unsupported", decode(stream() + inherited("late")).status)
        assertEquals("unsupported", decode(stream().replace(finish, finish + append("late"))).status)
        assertEquals("incomplete", decode(stream() + "data: {").status)
        assertEquals("incomplete", decode(ready + initial("") + finish + close).status)
        assertEquals("complete", decode(ready + initial("single snapshot") + finish + close).status)
    }

    @Test fun unknownPathsOperationsEventsAndMalformedValuesAreUnsupported() {
        for (patch in listOf(inherited("no inherited operation"), initial("duplicate"), frame("null"), frame("not-json"),
            frame("{}"), frame("""{"p":"response/fragments/-1/content","o":"SET","v":"replacement"}"""),
            frame("""{"p":"response/fragments/-1/content","o":"APPEND","v":3}"""), frame("""{"p":null,"v":"text"}"""),
            frame("""{"p":"response/unknown","o":"APPEND","v":"future"}"""), frame("{}", "future"),
            frame("""{"p":"response","o":"BATCH","v":[{"p":"fragments","v":[]}]}""")))
            assertEquals(patch, "unsupported", decode(ready + initial() + patch + finish + close).status)
    }

    @Test fun omittedPathAndOperationAreInheritedIndependently() {
        val body = ready + initial() + append("a") + frame("""{"o":"APPEND","v":"b"}""") +
            frame("""{"p":"response/fragments/-1/content","v":"c"}""") + inherited("d") + finish + close
        assertEquals("complete", decode(body).status)
        assertEquals("开头abcd", decode(body).answer)
    }

    @Test fun remoteErrorsOverrideSuccessfulHttpAndPreviouslyFinishedText() {
        for (error in listOf(frame("""{"code":7,"msg":"synthetic"}"""), frame("""{"error":"synthetic"}"""),
            frame("{}", "error"), frame("""{"p":"response/status","o":"SET","v":"FAILED"}""")))
            assertEquals("failed", decode(ready + initial() + error + finish + close).status)
        assertEquals("failed", decode(stream().replace(finish, finish + frame("""{"error_code":9}"""))).status)
    }

    @Test fun truncationBodyAvailabilityAndTransportGateOtherwiseCompleteAnswers() {
        for (e in listOf(exchange().copy(completion = Completion.ACTIVE), exchange().copy(completion = Completion.INTERRUPTED),
            exchange().copy(endedAt = null), exchange().copy(requestBody = null), exchange().copy(responseBody = null),
            exchange().copy(requestBody = reqRef.copy(inProgress = true)), exchange().copy(responseBody = resRef.copy(inProgress = true)),
            exchange().copy(requestBody = reqRef.copy(truncated = true)), exchange().copy(responseBody = resRef.copy(truncated = true)),
            exchange().copy(responseBody = resRef.copy(observedBytes = 1001))))
            assertEquals("incomplete", decode(e = e).status)
        for (p in listOf(BodyPreview(stream(), false, true), BodyPreview(stream(), true, false)))
            assertEquals("incomplete", decode(sp = p).status)
        assertEquals("incomplete", decode(rp = BodyPreview(request(), false, true)).status)
        for (e in listOf(exchange().copy(status = 503), exchange().copy(error = "synthetic"), exchange().copy(completion = Completion.FAILED)))
            assertEquals("failed", decode(e = e).status)
    }

    @Test fun rawBodiesAndStreamsBeyondUiWindowAreNotTruncated() {
        val body = ready + initial() + append("first,") + (1..600).joinToString("") { inherited("$it,") } + finish + close
        val result = decode(rp = BodyPreview("preview", false, false, rawText = request()),
            sp = BodyPreview("preview", false, false, rawText = body))
        assertEquals("complete", result.status)
        assertEquals(605, result.eventCount)
        assertTrue(result.answer.startsWith("开头first,1,2,"))
        assertTrue(result.answer.endsWith("599,600,"))
        assertEquals("complete", decode(stream().replace("\n", "\r\n")).status)
    }

    @Test fun readerCatalogSelectionAndJsonExportUseTheExistingContract() = runTest {
        val e = exchange().copy(packageName = null, requestHeaders = listOf(Header("Authorization", "synthetic-private-token")))
        val repo = object : CaptureRepository {
            override suspend fun session(id: String) = CaptureSession(id = "s1", title = "synthetic", packages = listOf("com.deepseek.chat"))
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
        val selector = AnswerSelector(provider = "deepseek", prompt = "合成问题")
        assertEquals("verified_sample", PlatformCatalog.forPackage("com.deepseek.chat")?.support)
        assertEquals("ambiguous", reader.read("s1", selector).selectionStatus)
        val result = reader.read("s1", selector.copy(exchangeId = "e2", startedAfter = 300))
        assertEquals("unique_complete", result.selectionStatus)
        assertEquals("com.deepseek.chat", result.results.single().packageName)
        assertEquals("none", reader.read("s1", selector.copy(prompt = "合成问题 ")).selectionStatus)
        assertEquals("none", reader.read("s1", selector.copy(provider = "qianwen")).selectionStatus)
        assertFalse(AnswerJson.encode(result).contains("synthetic-private-token"))
    }
}
