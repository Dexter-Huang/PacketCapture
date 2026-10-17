package com.packetcapture.llm

import com.packetcapture.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AnswerReaderTest {
    private val req = BodyRef("request", savedBytes = 50)
    private val res = BodyRef("response", savedBytes = 500)
    private fun exchange() = HttpExchange(id = "e1", sessionId = "s1", connectionId = "c1",
        method = "POST", url = "https://api.yuanbao.tencent.com/api/chat/test", protocol = "HTTP/1.1",
        startedAt = 100, endedAt = 200, status = 200, requestBody = req, responseBody = res,
        responseHeaders = listOf(Header("Content-Type", "text/event-stream; charset=utf-8")), completion = Completion.COMPLETE)
    private val terminal = "data: {\"type\":\"meta\",\"stopReason\":\"stop\",\"modelErrorCode\":0}\n\ndata: [DONE]\n\n"
    private fun frame(text: String) = "data: {\"type\":\"text\",\"msg\":\"$text\"}\n\n"
    private val good = frame("你好") + "event: speech_type\ndata: text\n\n" +
        "data: [MSGINDEX:2]\n\ndata: [TRACEID:synthetic]\n\n" + terminal

    private suspend fun read(text: String = good, exchanges: List<HttpExchange> = listOf(exchange()),
        selector: AnswerSelector = AnswerSelector(), limited: Boolean = false, packages: List<String> = listOf("com.tencent.hunyuan.app.chat")): AnswerBatch {
        val repo = Repo(exchanges, packages)
        val bodies = Bodies(mapOf("request" to BodyPreview("{\"prompt\":\"问题\"}", false, false),
            "response" to BodyPreview(text, false, limited)))
        return AnswerReader(repo, bodies).read("s1", selector)
    }

    @Test fun nativeTextIsAssembledAndSinglePackageFallbackIsExplicit() = runTest {
        val batch = read()
        assertEquals("unique_complete", batch.selectionStatus)
        assertEquals("你好", batch.results.single().answer)
        assertEquals("问题", batch.results.single().prompt)
        assertEquals("com.tencent.hunyuan.app.chat", batch.results.single().packageName)
        assertEquals("not_collected", batch.results.single().referenceCaptureStatus)
    }

    @Test fun truncatedDisconnectedOrUnfinishedDataNeverSucceeds() = runTest {
        val variants = listOf(
            exchange().copy(completion = Completion.INTERRUPTED),
            exchange().copy(responseBody = res.copy(truncated = true)),
            exchange().copy(responseBody = res.copy(inProgress = true)),
            exchange().copy(endedAt = null),
            exchange().copy(requestBody = req.copy(inProgress = true)),
        )
        for (e in variants) assertEquals("unique_incomplete", read(exchanges = listOf(e)).selectionStatus)
        assertEquals("unique_incomplete", read(limited = true).selectionStatus)
        assertEquals("unique_incomplete", read(frame("partial")).selectionStatus)
        assertEquals("unique_incomplete", read(good + "data: {").selectionStatus)
        assertEquals("unique_incomplete", read("data: [DONE]\n\n").selectionStatus)
    }

    @Test fun remoteErrorOverridesDoneAndHttp200() = runTest {
        val error = frame("partial") + "data: {\"type\":\"meta\",\"stopReason\":\"stop\",\"modelErrorCode\":7}\n\ndata: [DONE]\n\n"
        assertEquals("unique_failed", read(error).selectionStatus)
        assertEquals("unique_failed", read(exchanges = listOf(exchange().copy(status = 503))).selectionStatus)
    }

    @Test fun unknownSchemaCannotMasqueradeAsSuccess() = runTest {
        assertEquals("unique_unsupported", read("data: {\"type\":\"new_answer\",\"text\":\"x\"}\n\n" + good).selectionStatus)
        assertEquals("unique_unsupported", read(exchanges = listOf(exchange().copy(url = "https://api.yuanbao.tencent.com/api/history"))).selectionStatus)
        val doubao = exchange().copy(packageName = "com.larus.nova")
        assertEquals("unique_unsupported", read(exchanges = listOf(doubao)).selectionStatus)
        assertEquals("", read(exchanges = listOf(doubao)).results.single().answer)
    }

    @Test fun sourceLinksAreSeparatedAndOnlyHttpUrlsAreExported() = runTest {
        val source = "data: {\"type\":\"searchGuid\",\"docs\":[{\"index\":1,\"title\":\"来源\",\"url\":\"https://example.test/a\"},{\"title\":\"bad\",\"url\":\"javascript:evil\"}]}\n\n"
        val answer = read(source + good).results.single()
        assertEquals("你好", answer.answer)
        assertEquals(listOf(SearchSource("来源", "https://example.test/a", 1)), answer.searchSources)
        assertEquals("collected", answer.referenceCaptureStatus)
        assertEquals("", answer.reasoning)
    }

    @Test fun exactTaskSelectionDoesNotPickTheLatestDuplicate() = runTest {
        val two = listOf(exchange(), exchange().copy(id = "e2", startedAt = 300, endedAt = 400))
        assertEquals("ambiguous", read(exchanges = two, selector = AnswerSelector(prompt = "问题")).selectionStatus)
        assertEquals("none", read(selector = AnswerSelector(prompt = "问题 ")).selectionStatus)
        val selected = read(exchanges = two, selector = AnswerSelector(provider = "yuanbao", prompt = "问题", startedAfter = 250))
        assertEquals("e2", selected.results.single().exchangeId)
        assertEquals("e1", read(exchanges = two, selector = AnswerSelector(exchangeId = "e1")).results.single().exchangeId)
    }

    @Test fun unknownAttributionInMultiAppCaptureIsNotGuessed() = runTest {
        assertEquals("none", read(packages = listOf("com.tencent.hunyuan.app.chat", "com.larus.nova")).selectionStatus)
    }

    @Test fun answerDoesNotLoseTheFirstChunksAfter500EventsOrUi200Rows() = runTest {
        val body = (1..600).joinToString("") { frame("$it,") } + terminal
        val batch = read(body, (1..201).map { exchange().copy(id = "e$it") }, AnswerSelector(exchangeId = "e201"))
        assertEquals("unique_complete", batch.selectionStatus)
        assertTrue(batch.results.single().answer.startsWith("1,2,3,"))
        assertTrue(batch.results.single().answer.endsWith("600,"))
        assertEquals(602, batch.results.single().eventCount)
    }

    @Test fun serializedContractContainsNoPrivateTransportHeaders() = runTest {
        val json = AnswerJson.encode(read(exchanges = listOf(exchange().copy(requestHeaders = listOf(Header("Authorization", "private-token"))))))
        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertFalse(json.contains("private-token"))
        assertFalse(json.contains("requestHeaders"))
    }

    private class Bodies(private val previews: Map<String, BodyPreview>) : BodyStore {
        override suspend fun preview(ref: BodyRef?, headers: List<Header>) = previews[ref?.key] ?: BodyPreview("", false, true)
        override fun open(sessionId: String, exchangeId: String, part: BodyPart, limit: Long, totalLimit: Long): BodySink = error("unused")
        override suspend fun read(ref: BodyRef, maxBytes: Int): ByteArray = error("unused")
        override suspend fun deleteSession(sessionId: String) = error("unused")
        override suspend fun clearAll() = error("unused")
        override suspend fun usedBytes(): Long = error("unused")
        override suspend fun recoverOrphans(liveSessionIds: Set<String>) = error("unused")
    }

    private class Repo(private val records: List<HttpExchange>, private val packages: List<String>) : CaptureRepository {
        override suspend fun session(id: String) = CaptureSession(id = id, title = "synthetic", packages = packages)
        override suspend fun allExchanges(sessionId: String) = records
        override suspend fun allConnections(sessionId: String) = emptyList<ConnectionRecord>()
        override fun sessions(query: String): Flow<List<CaptureSession>> = error("unused")
        override fun exchanges(sessionId: String?, filter: ExchangeFilter, limit: Int): Flow<List<HttpExchange>> = error("UI window must not be used")
        override fun connections(sessionId: String?): Flow<List<ConnectionRecord>> = flowOf(emptyList())
        override fun exchange(id: String): Flow<HttpExchange?> = error("unused")
        override suspend fun connection(id: String): ConnectionRecord? = error("unused")
        override suspend fun saveSession(session: CaptureSession) = error("unused")
        override suspend fun saveConnection(connection: ConnectionRecord) = error("unused")
        override suspend fun saveExchange(exchange: HttpExchange) = error("unused")
        override suspend fun deleteSession(id: String) = error("unused")
        override suspend fun clearStorage() = error("unused")
        override suspend fun recoverInterrupted() = error("unused")
    }
}
