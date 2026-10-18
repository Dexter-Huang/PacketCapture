package com.packetcapture.llm

import com.google.gson.Gson
import com.packetcapture.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DoubaoDecoderTest {
    private val gson = Gson()
    private val decoder = DoubaoDecoder()
    private val requestBody = BodyRef("request", observedBytes = 100, savedBytes = 100)
    private val responseBody = BodyRef("response", observedBytes = 1000, savedBytes = 1000)
    private fun exchange() = HttpExchange(id = "e1", sessionId = "s1", connectionId = "c1",
        packageName = "com.larus.nova", method = "POST", url = "https://api5-normal-gl.doubao.com/chat/completion",
        protocol = "HTTP/2", startedAt = 100, endedAt = 200, status = 200, completion = Completion.COMPLETE,
        requestBody = requestBody, responseBody = responseBody,
        responseHeaders = listOf(Header("Content-Type", "text/event-stream")))

    private fun frame(type: String, payload: String) = "id: 0\nevent: $type\ndata: $payload\n\n"
    private fun block(text: String, finish: Boolean = false, id: String = "block", patch: Int = 1) =
        """{"block_type":10000,"block_id":"$id","content":{"text_block":{"text":${gson.toJson(text)}}},"is_finish":$finish,"patch_type":$patch}"""
    private fun patch(text: String = "", finish: Boolean = false, id: String = "block", type: Int = 1) =
        frame("STREAM_CHUNK", """{"message_id":"assistant","patch_op":[{"patch_object":1,"patch_type":1,"patch_value":{"content_block":[${block(text, finish, id, type)}]}}]}""")
    private fun delta(text: String) = frame("CHUNK_DELTA", """{"text":${gson.toJson(text)}}""")
    private val ack = frame("SSE_ACK", """{"query_list":[{"local_message_id":"local"}]}""")
    private val start = ack + frame("STREAM_MSG_NOTIFY", """{"content":{"content_block":[${block("#")}]},"meta":{"message_id":"assistant","user_type":2}}""")
    private val end1 = frame("SSE_REPLY_END", """{"end_type":1,"msg_finish_attr":{"msgid":"assistant"}}""")
    private val end2 = frame("SSE_REPLY_END", """{"end_type":2,"answer_finish_attr":{"has_suggest":true}}""")
    private val end3 = frame("SSE_REPLY_END", """{"end_type":3}""")
    private val suggestions = frame("STREAM_CHUNK", """{"message_id":"assistant","patch_op":[{"patch_object":50,"patch_type":1,"patch_value":{"ext":{"sp_v2":"suggestion text is not an answer"}}}]}""")
    private val good = start + patch(" ") + delta("你好") + delta("🌈\n第二行") + patch(finish = true) + end1 + end2 + suggestions + end3
    private fun request(prompt: String = "QW-independent test question") =
        """{"messages":[{"local_message_id":"local","content_block":[${block(prompt)}]}],"option":{"history_messages":[{"text":"old question"}]}}"""
    private fun decode(text: String = good, req: String = request(), e: HttpExchange = exchange(),
        requestPreview: BodyPreview = BodyPreview(req, false, false), responsePreview: BodyPreview = BodyPreview(text, false, false)) =
        decoder.decode(e, requestPreview, responsePreview)

    @Test fun realEventOrderPreservesInitialPatchAndUnicodeDeltas() {
        val result = decode()
        assertEquals("complete", result.status)
        assertEquals("# 你好🌈\n第二行", result.answer)
        assertEquals("QW-independent test question", result.prompt)
        assertEquals("doubao-chat-sse/1", result.decoder)
        assertEquals(10, result.eventCount)
        assertEquals(emptyList<String>(), result.diagnostics)
        assertEquals("", result.reasoning)
    }

    @Test fun echoHeartbeatAndRecommendedMediaDoNotPolluteAnswerOrSearchSources() {
        val echo = frame("FULL_MSG_NOTIFY", """{"message":{"user_type":1,"local_message_id":"local","content_block":[${block("QW-independent test question")}]}}""")
        val media = frame("STREAM_CHUNK", """{"message_id":"assistant","patch_op":[{"patch_object":1,"patch_type":1,"patch_value":{"content_block":[{"block_type":10050,"block_id":"video","patch_type":2,"is_finish":true,"content":{"rich_media_block":{"creations":[{"video":{"main_site_url":"https://example.test/video","video_captions":"not a citation"}}]}}}]}},{"patch_object":50,"patch_type":1,"patch_value":{"ext":{}}}]}""")
        val control = frame("STREAM_CHUNK", """{"message_id":"assistant","patch_op":[{"patch_object":3,"patch_type":2,"patch_value":{}}]}""")
        val result = decode(frame("SSE_HEARTBEAT", "{}") + good.replace(ack, ack + echo).replace(end1, media + control + end1))
        assertEquals("complete", result.status)
        assertEquals("# 你好🌈\n第二行", result.answer)
        assertEquals(emptyList<SearchSource>(), result.searchSources)
        assertEquals("not_collected", result.referenceCaptureStatus)
    }

    @Test fun onlyTheObservedLiveEndpointMatches() {
        assertTrue(decoder.matches(exchange()))
        assertTrue(decoder.matches(exchange().copy(url = exchange().url + "?synthetic=1")))
        for (url in listOf("https://api5-normal-gl.doubao.com/history", "https://example.test/chat/completion",
            "https://api5-normal-gl.doubao.com.example.test/chat/completion", "http://api5-normal-gl.doubao.com/chat/completion")) {
            assertFalse(decoder.matches(exchange().copy(url = url)))
        }
        assertFalse(decoder.matches(exchange().copy(method = "GET")))
    }

    @Test fun promptUsesCurrentMessagesAndPreservesExactWhitespace() {
        val prompt = " 问题\n🌈 "
        assertEquals(prompt, decode(req = request(prompt)).prompt)
        assertEquals("# 你好🌈\n第二行", decode(req = request(prompt)).answer)
        val multiple = """{"messages":[{"content_block":[${block("one")}]},{"content_block":[${block("two")}]}]}"""
        assertEquals("unsupported", decode(req = multiple).status)
        assertNull(decode(req = multiple).prompt)
        assertTrue(decode(req = multiple).diagnostics.contains("ambiguous_request_messages"))
        assertEquals("incomplete", decode(req = "{}").status)
        assertEquals("incomplete", decode(req = request(" ")).status)
    }

    @Test fun binaryAndTtnetEncryptedPayloadsAreNeverParsedAsPlainSse() {
        assertEquals("incomplete", decode(responsePreview = BodyPreview("ciphertext", true, false)).status)
        val flagged = exchange().copy(responseHeaders = exchange().responseHeaders + Header("X-TT-E-B", "1"))
        assertEquals("complete", decode(e = flagged).status)
        val result = decode(e = flagged, responsePreview = BodyPreview("ciphertext", true, false))
        assertEquals("unsupported", result.status)
        assertEquals("", result.answer)
        assertTrue(result.diagnostics.contains("application_payload_encrypted"))
    }

    @Test fun allThreeTerminalStagesAndFinishedTextAreRequired() {
        for (body in listOf(good.replace(end1, ""), good.replace(end2, ""), good.replace(end3, ""),
            good.replace(patch(finish = true), ""), start + delta("partial"))) {
            assertNotEquals("complete", decode(body).status)
        }
        assertEquals("unsupported", decode(good.replace(end1 + end2, end2 + end1)).status)
        assertEquals("unsupported", decode(good + delta("late")).status)
        assertEquals("unsupported", decode(good.replace(end3, end2 + end3)).status)
    }

    @Test fun activeTruncatedInterruptedOrFailedTransportCannotSucceed() {
        val variants = listOf(exchange().copy(endedAt = null), exchange().copy(completion = Completion.ACTIVE),
            exchange().copy(completion = Completion.INTERRUPTED), exchange().copy(requestBody = requestBody.copy(inProgress = true)),
            exchange().copy(responseBody = responseBody.copy(inProgress = true)),
            exchange().copy(responseBody = responseBody.copy(truncated = true)),
            exchange().copy(requestBody = requestBody.copy(truncated = true)),
            exchange().copy(responseBody = responseBody.copy(observedBytes = 1001)),
            exchange().copy(requestBody = null), exchange().copy(responseBody = null))
        for (e in variants) assertEquals("incomplete", decode(e = e).status)
        assertEquals("incomplete", decode(responsePreview = BodyPreview(good, false, true)).status)
        assertEquals("incomplete", decode(requestPreview = BodyPreview(request(), false, true)).status)
        assertEquals("failed", decode(e = exchange().copy(status = 503)).status)
        assertEquals("failed", decode(e = exchange().copy(completion = Completion.FAILED)).status)
        assertEquals("failed", decode(e = exchange().copy(error = "synthetic error")).status)
    }

    @Test fun unknownEventsPatchesBlockTypesAndMalformedJsonRemainUnsupported() {
        for (extra in listOf(frame("NEW_EVENT", "{}"), frame("CHUNK_DELTA", "not-json"),
            patch("replacement", type = 2), patch("other block", id = "another"),
            frame("CHUNK_DELTA", """{"text":12}"""),
            frame("STREAM_CHUNK", """{"message_id":"assistant","patch_op":[{"patch_object":99,"patch_type":1,"patch_value":{}}]}"""),
            frame("STREAM_CHUNK", """{"message_id":"assistant","patch_op":[{"patch_object":1,"patch_type":1,"patch_value":{"content_block":[{"block_type":20000}]}}]}"""))) {
            assertEquals("unsupported", decode(good.replace(patch(finish = true), extra + patch(finish = true))).status)
        }
    }

    @Test fun messageIdentityAndAckMustMatchTheCurrentRequest() {
        assertEquals("unsupported", decode(good.replace("\"local_message_id\":\"local\"", "\"local_message_id\":\"other\"")).status)
        assertEquals("unsupported", decode(good.replace("\"msgid\":\"assistant\"", "\"msgid\":\"other\"")).status)
        assertEquals("unsupported", decode(good.replace("\"message_id\":\"assistant\",\"patch_op\"", "\"message_id\":\"other\",\"patch_op\"")).status)
        assertEquals("unsupported", decode(start + start + delta("x") + patch(finish = true) + end1 + end2 + end3).status)
    }

    @Test fun remoteErrorOverridesHttp200AndAnOtherwiseNormalEnding() {
        assertEquals("failed", decode(good.replace(end1, frame("SSE_ERROR", """{"error_code":7}""") + end1)).status)
        assertEquals("failed", decode(good.replace("\"text\":\"你好\"", "\"text\":\"你好\",\"error_code\":1")).status)
        assertEquals("failed", decode(good.replace("\"has_suggest\":true", "\"has_suggest\":true,\"error_code\":7")).status)
    }

    @Test fun pendingFrameAndEmptyAnswersAreNotComplete() {
        assertEquals("incomplete", decode(good + "data: {").status)
        val empty = start.replace(block("#"), block("")) + patch(finish = true) + end1 + end2 + end3
        assertEquals("incomplete", decode(empty).status)
    }

    @Test fun rawTextAndWholeEventHistoryAreUsedInsteadOfUiPreviews() {
        val body = start + (1..600).joinToString("") { delta("$it,") } + patch(finish = true) + end1 + end2 + end3
        val result = decode(responsePreview = BodyPreview("UI preview", false, false, rawText = body),
            requestPreview = BodyPreview("UI preview", false, false, rawText = request()))
        assertEquals("complete", result.status)
        assertTrue(result.answer.startsWith("#1,2,3,"))
        assertTrue(result.answer.endsWith("599,600,"))
        assertEquals(606, result.eventCount)
    }

    @Test fun readerRegistersDoubaoAndPreservesTaskSelectionAndPrivateHeaders() = runTest {
        val e = exchange().copy(packageName = null, requestHeaders = listOf(Header("Authorization", "secret-test-token")))
        val repo = object : CaptureRepository {
            override suspend fun session(id: String) = CaptureSession(id = "s1", title = "synthetic", packages = listOf("com.larus.nova"))
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
            override suspend fun preview(ref: BodyRef?, headers: List<Header>) = BodyPreview(if (ref?.key == "request") request() else good, false, false)
            override fun open(sessionId: String, exchangeId: String, part: BodyPart, limit: Long, totalLimit: Long): BodySink = error("unused")
            override suspend fun read(ref: BodyRef, maxBytes: Int): ByteArray = error("unused")
            override suspend fun deleteSession(sessionId: String) = error("unused")
            override suspend fun clearAll() = error("unused")
            override suspend fun usedBytes(): Long = error("unused")
            override suspend fun recoverOrphans(liveSessionIds: Set<String>) = error("unused")
        }
        val reader = AnswerReader(repo, bodies)
        val selection = AnswerSelector(provider = "doubao", prompt = "QW-independent test question")
        assertEquals("ambiguous", reader.read("s1", selection).selectionStatus)
        val result = reader.read("s1", selection.copy(exchangeId = "e2"))
        assertEquals("unique_complete", result.selectionStatus)
        assertEquals("com.larus.nova", result.results.single().packageName)
        assertEquals("none", reader.read("s1", selection.copy(prompt = "QW-independent test question ")).selectionStatus)
        assertFalse(AnswerJson.encode(result).contains("secret-test-token"))
    }
}
