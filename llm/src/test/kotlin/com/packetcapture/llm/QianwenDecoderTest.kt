package com.packetcapture.llm

import com.google.gson.Gson
import com.packetcapture.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic protocol fixtures only; no captured account data or real conversations. */
class QianwenDecoderTest {
    private val gson = Gson()
    private val decoder = QianwenDecoder()
    private val requestRef = BodyRef("request", observedBytes = 100, savedBytes = 100)
    private val responseRef = BodyRef("response", observedBytes = 1000, savedBytes = 1000)
    private fun exchange() = HttpExchange(id = "e1", sessionId = "s1", connectionId = "c1",
        packageName = "com.aliyun.tongyi", method = "POST", url = "https://chat2.qianwen.com/api/v1/chat",
        protocol = "HTTP/2", startedAt = 100, endedAt = 200, status = 200, completion = Completion.COMPLETE,
        requestBody = requestRef, responseBody = responseRef,
        responseHeaders = listOf(Header("Content-Type", "text/event-stream;charset=UTF-8")))
    private fun request(prompt: String = "合成问题") = """{"req_id":"req","session_id":"session","protocol_version":"v2","incremental_strategy":1,"json_patch_enabled":true,"chat_mode":"quick","messages":[{"mime_type":"text/plain","content":${gson.toJson(prompt)}}],"history":[{"content":"旧问题"}]}"""
    private fun frame(seq: Int, messages: String, type: String = "message", extra: String = "{}") =
        "event: $type\ndata: {\"error_code\":0,\"error_msg\":\"\",\"communication\":{\"reqid\":\"req\",\"sessionid\":\"session\",\"resid\":$seq,\"disconnection_signal\":0},\"data\":{\"audit_info\":{\"error_code\":0},\"messages\":$messages,\"extra_info\":$extra}}\n\n"
    private fun snapshot(mime: String, text: String = "", status: String = "processing") =
        """{"mime_type":"$mime","content":${gson.toJson(text)},"status":"$status","meta_data":{"multi_load":[]}}"""
    private fun patch(mime: String, ops: String) = """{"mime_type":"$mime","patch":[$ops]}"""
    private fun add(text: String) = """{"op":"add","path":"/content","value":${gson.toJson(text)}}"""
    private val finish = """{"op":"replace","path":"/status","value":"complete"}"""
    private val docs = """[{"title":"参考一","url":"https://example.test/one"},{"title":"重复","url":"https://example.test/one"},{"title":"参考二","url":"http://example.test/two"},{"title":"无效","url":"javascript:alert(1)"},{"title":"无域名","url":"https:/relative"},{"title":"凭据","url":"https://user:password@example.test/private"}]"""
    private fun sourcePatch(documents: String = docs) = patch("bar/workflow", """{"op":"add","path":"/meta_data/multi_load/-","value":{"type":"bar_ref_source_inline","source_seq":"source","content":{"status":"complete","docs":$documents}}}""")
    private val messages: List<String> get() = listOf(
        snapshot("signal/post", status = "complete"),
        snapshot("bar/workflow"),
        patch("bar/workflow", """{"op":"replace","path":"/meta_data/bar_title","value":"正在检索"}"""),
        sourcePatch(),
        snapshot("multi_load/iframe", "开头"),
        patch("multi_load/iframe", """{"op":"remove","path":"/meta_data/first_packet"},${add("🌈\n第二行")}"""),
        patch("multi_load/iframe", add("保留 Markdown **文本**")),
        snapshot("bar/workflow", status = "complete"),
        patch("multi_load/iframe", """{"op":"add","path":"/meta_data/multi_load/-","value":{"type":"inline_action_reply","source_seq":"inline_action_reply_1","content":{"text":"按钮不是答案"}}},${add("\r\n[[inline_action_reply_1]]")},$finish"""))
    private fun stream(items: List<String> = messages, terminal: Boolean = true) = items.mapIndexed { i, m -> frame(i, "[$m]") }.joinToString("") +
        if (terminal) frame(items.size, "[]", "complete", """{"sse_end":"1","telemetry":{"content":"不能重复加入答案"}}""") else ""
    private val expected = "开头🌈\n第二行保留 Markdown **文本**\r\n[[inline_action_reply_1]]"
    private fun decode(body: String = stream(), req: String = request(), e: HttpExchange = exchange(),
        reqPreview: BodyPreview = BodyPreview(req, false, false), resPreview: BodyPreview = BodyPreview(body, false, false)) =
        decoder.decode(e, reqPreview, resPreview)

    @Test fun reconstructsInitialUnicodeAndAppendPatchesWithoutControlText() {
        val result = decode()
        assertEquals("complete", result.status)
        assertEquals(expected, result.answer)
        assertEquals("合成问题", result.prompt)
        assertEquals("qianwen-chat-sse/1", result.decoder)
        assertEquals(10, result.eventCount)
        assertEquals(emptyList<String>(), result.diagnostics)
        assertEquals("", result.reasoning)
    }

    @Test fun quickWriterProgressAndEmptyFollowupRequireCompleteSequence() {
        val followup = """{"mime_type":"paa/iframe","status":"complete","meta_data":{"show_type":"","scene":"","exclusive_card_types":[],"paas":[],"append_msg":{"mime_type":"","content":"","status":"","meta_data":{"multi_load":[]}}}}"""
        val items = listOf(messages.first(),
            """{"mime_type":"bar/progress","status":"processing","meta_data":{"type":"cot"}}""",
            """{"mime_type":"bar/iframe","status":"complete","meta_data":{"sources":[]}}""",
            snapshot("multi_load/iframe", "合成正文"), patch("multi_load/iframe", finish),
            """{"mime_type":"bar/progress","status":"complete","meta_data":{"type":"generated"}}""", followup)
        val body = stream(items).replace("\"error_msg\":\"\",\"communication\":{\"reqid\":\"req\",\"sessionid\":\"session\",\"resid\":6", "\"communication\":{\"reqid\":\"req\",\"sessionid\":\"session\",\"resid\":6")
        assertEquals("complete", decode(body).status)
        assertEquals("合成正文", decode(body).answer)
        assertEquals("incomplete", decode(stream(items, terminal = false)).status)
        assertEquals("unsupported", decode(stream(items.map { it.replace("\"paas\":[]", "\"paas\":[{}]") })).status)
        assertEquals("unsupported", decode(stream(items.map { it.replace("\"sources\":[]", "\"sources\":[{}]") })).status)
        assertEquals("incomplete", decode(stream(items.filterNot { it.contains("generated") })).status)
    }

    @Test fun sourcesSurviveFinalEmptySnapshotAndAreDeduplicatedValidatedAndSeparate() {
        val result = decode()
        assertEquals(listOf(SearchSource("参考一", "https://example.test/one", null), SearchSource("参考二", "http://example.test/two", null)), result.searchSources)
        assertEquals("collected", result.referenceCaptureStatus)
        val noSources = decode(stream(messages.toMutableList().apply { set(3, sourcePatch("[]")) }))
        assertEquals("complete", noSources.status)
        assertEquals("not_collected", noSources.referenceCaptureStatus)
        assertTrue(noSources.searchSources.isEmpty())
    }

    @Test fun videoRecommendationsRemainMetadataAndNeverBecomeAnswerOrReferences() {
        val video = """{"type":"video_note_list","html":"","source_seq":"video_note_list_1","content":{"intro":"","list":[{"title":"合成视频标题不能拼进回答","url":"https://example.test/video","intro":"视频说明","track_info":{"content":"不是回答"}}]}}"""
        val items = messages.toMutableList().apply {
            set(8, patch("multi_load/iframe", """{"op":"add","path":"/meta_data/multi_load/-","value":$video},${this@QianwenDecoderTest.add("[[video_note_list_1]]")},$finish"""))
        }
        val result = decode(stream(items))
        assertEquals("complete", result.status)
        assertEquals("开头🌈\n第二行保留 Markdown **文本**[[video_note_list_1]]", result.answer)
        assertEquals(2, result.searchSources.size)
        assertFalse(result.searchSources.any { it.url.endsWith("/video") })
        assertFalse(result.answer.contains("合成视频"))
        for (bad in listOf(video.replace("\"list\":[", "\"list\":[1,"),
            video.replace("\"html\":\"\"", "\"html\":\"<script>unknown</script>\""),
            video.replace("\"intro\":\"\",", ""), video.replace("video_note_list\"", "new_video_type\""))) {
            assertEquals("unsupported", decode(stream(items.toMutableList().apply {
                set(8, patch("multi_load/iframe", """{"op":"add","path":"/meta_data/multi_load/-","value":$bad},$finish"""))
            })).status)
        }
    }

    @Test fun onlyObservedEndpointAndMethodMatch() {
        assertTrue(decoder.matches(exchange()))
        assertTrue(decoder.matches(exchange().copy(url = exchange().url + "?synthetic=1")))
        for (url in listOf("https://chat.qianwen.com/api/v1/chat", "https://chat2.qianwen.com/history", "http://chat2.qianwen.com/api/v1/chat", "https://chat2.qianwen.com.example.test/api/v1/chat"))
            assertFalse(decoder.matches(exchange().copy(url = url)))
        assertFalse(decoder.matches(exchange().copy(method = "GET")))
    }

    @Test fun promptPreservesWhitespaceAndRejectsHistoryAmbiguityAndOtherModes() {
        assertEquals(" 问题\n🌈 ", decode(req = request(" 问题\n🌈 ")).prompt)
        assertEquals("incomplete", decode(req = "{}").status)
        assertEquals("incomplete", decode(req = request(" ")).status)
        for ((old, new) in listOf("\"quick\"" to "\"deep\"", "\"v2\"" to "\"v3\"", "\"incremental_strategy\":1" to "\"incremental_strategy\":2", "\"json_patch_enabled\":true" to "\"json_patch_enabled\":false", "\"mime_type\":\"text/plain\"" to "\"mime_type\":\"image/jpeg\""))
            assertEquals("unsupported", decode(req = request().replace(old, new)).status)
        val multiple = request().replace("\"messages\":[", "\"messages\":[{\"mime_type\":\"text/plain\",\"content\":\"other\"},")
        assertEquals("unsupported", decode(req = multiple).status)
        assertNull(decode(req = multiple).prompt)
    }

    @Test fun identitiesAndMonotonicContiguousSequenceAreRequired() {
        for ((old, new) in listOf("\"reqid\":\"req\"" to "\"reqid\":\"other\"", "\"sessionid\":\"session\"" to "\"sessionid\":\"other\"", "\"resid\":2" to "\"resid\":1", "\"resid\":2" to "\"resid\":3", "\"resid\":2" to "\"resid\":\"2\""))
            assertEquals("unsupported", decode(stream().replace(old, new)).status)
        assertEquals("incomplete", decode(req = request().replace("\"req_id\":\"req\",", "")).status)
    }

    @Test fun remoteAuditAndDisconnectErrorsOverrideOtherwiseSuccessfulHttp() {
        for ((old, new) in listOf("\"error_code\":0" to "\"error_code\":9", "\"error_msg\":\"\"" to "\"error_msg\":\"synthetic error\"", "\"disconnection_signal\":0" to "\"disconnection_signal\":1", "event: message" to "event: error"))
            assertEquals("failed", decode(stream().replaceFirst(old, new)).status)
        assertEquals("failed", decode(stream().replace("\"audit_info\":{\"error_code\":0}", "\"audit_info\":{\"error_code\":7}")).status)
    }

    @Test fun terminalTextAndWorkflowCompletionAllRequired() {
        assertEquals("incomplete", decode(stream(terminal = false)).status)
        assertEquals("unsupported", decode(stream().replace("\"sse_end\":\"1\"", "\"sse_end\":\"0\"")).status)
        assertNotEquals("complete", decode(stream(messages.toMutableList().apply { set(8, patch("multi_load/iframe", this@QianwenDecoderTest.add("tail"))) })).status)
        assertNotEquals("complete", decode(stream(messages.toMutableList().apply { set(7, patch("bar/workflow", """{"op":"replace","path":"/meta_data/bar_title","value":"还在处理"}""")) })).status)
        assertEquals("unsupported", decode(stream() + frame(10, "[${messages[6]}]")).status)
        assertEquals("incomplete", decode(stream() + "data: {").status)
    }

    @Test fun unknownEventsPatchesMimesMalformedDataAndReplacementAreUnsupported() {
        for (m in listOf(patch("multi_load/iframe", """{"op":"replace","path":"/content","value":"replacement"}"""),
            patch("multi_load/iframe", """{"op":"add","path":"/reasoning","value":"thoughts"}"""),
            patch("multi_load/iframe", """{"op":"add","path":"/content","value":3}"""),
            patch("new/mime", add("unknown")), patch("multi_load/iframe", "{}"),
            snapshot("multi_load/iframe", "duplicate snapshot"), "null", "not-json",
            sourcePatch("[1]"))) {
            assertEquals("unsupported", decode(stream(messages.toMutableList().apply { set(6, m) })).status)
        }
        assertEquals("unsupported", decode(stream().replaceFirst("event: message", "event: future")).status)
    }

    @Test fun transportAndBodyQualityGateCompletionEvenWithAllEndMarkers() {
        for (e in listOf(exchange().copy(endedAt = null), exchange().copy(completion = Completion.ACTIVE),
            exchange().copy(completion = Completion.INTERRUPTED), exchange().copy(requestBody = null), exchange().copy(responseBody = null),
            exchange().copy(requestBody = requestRef.copy(inProgress = true)), exchange().copy(responseBody = responseRef.copy(inProgress = true)),
            exchange().copy(requestBody = requestRef.copy(truncated = true)), exchange().copy(responseBody = responseRef.copy(truncated = true)),
            exchange().copy(responseBody = responseRef.copy(observedBytes = 1001)))) assertEquals("incomplete", decode(e = e).status)
        for (p in listOf(BodyPreview(stream(), true, false), BodyPreview(stream(), false, true))) assertEquals("incomplete", decode(resPreview = p).status)
        assertEquals("incomplete", decode(reqPreview = BodyPreview(request(), false, true)).status)
        assertEquals("failed", decode(e = exchange().copy(completion = Completion.FAILED)).status)
        assertEquals("failed", decode(e = exchange().copy(status = 503)).status)
        assertEquals("failed", decode(e = exchange().copy(error = "synthetic failure")).status)
    }

    @Test fun emptyAnswerCannotSucceed() {
        val items = listOf(snapshot("multi_load/iframe"), patch("multi_load/iframe", finish))
        assertEquals("incomplete", decode(stream(items)).status)
    }

    @Test fun rawTextAndMoreThanFiveHundredEventsArePreserved() {
        val items = listOf(snapshot("multi_load/iframe", "start")) + (1..600).map { patch("multi_load/iframe", add("$it,")) } + patch("multi_load/iframe", finish)
        val result = decode(reqPreview = BodyPreview("preview", false, false, rawText = request()),
            resPreview = BodyPreview("preview", false, false, rawText = stream(items)))
        assertEquals("complete", result.status)
        assertEquals(603, result.eventCount)
        assertTrue(result.answer.startsWith("start1,2,3,"))
        assertTrue(result.answer.endsWith("599,600,"))
    }

    @Test fun readerRegistrationSelectionAndHeaderPrivacyUseExistingContract() = runTest {
        val e = exchange().copy(packageName = null, requestHeaders = listOf(Header("Authorization", "synthetic-secret")))
        val repo = object : CaptureRepository {
            override suspend fun session(id: String) = CaptureSession(id = "s1", title = "synthetic", packages = listOf("com.aliyun.tongyi"))
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
        val selector = AnswerSelector(provider = "qianwen", prompt = "合成问题")
        assertEquals("ambiguous", reader.read("s1", selector).selectionStatus)
        val result = reader.read("s1", selector.copy(exchangeId = "e2", startedAfter = 300))
        assertEquals("unique_complete", result.selectionStatus)
        assertEquals("com.aliyun.tongyi", result.results.single().packageName)
        assertEquals("none", reader.read("s1", selector.copy(prompt = "合成问题 ")).selectionStatus)
        assertEquals("none", reader.read("s1", selector.copy(provider = "doubao")).selectionStatus)
        assertFalse(AnswerJson.encode(result).contains("synthetic-secret"))
    }
}
