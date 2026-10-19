package com.packetcapture.llm

import com.google.gson.Gson
import com.packetcapture.core.*
import org.junit.Assert.*
import org.junit.Test

class YuanbaoDecoderTest {
    private val gson = Gson()
    private val visible = "示例品牌服务大众用户。"
    private val answer = visible + "[](@mark_underline=1)[](@mark_underline=2)"
    private fun frame(value: Any) = "data: ${gson.toJson(value)}\n\n"
    private fun mark(content: String = "示例品牌[](@replace=10001)服务大众用户。[](@mark_underline=1)",
        id: Any = 1, end: String = "[](@mark_underline=1)", extra: Boolean = false): String {
        val payload = mutableMapOf<String, Any>("mark_id" to id, "content" to content,
            "mark_start" to "mark_begin", "mark_end" to end)
        if (extra) payload["new_operation"] = "replace"
        return frame(mapOf("type" to "mark", "mark" to payload))
    }
    private fun read(mark: String, completion: Completion = Completion.COMPLETE, truncated: Boolean = false): CapturedAnswer {
        val stream = frame(mapOf("type" to "text", "msg" to answer)) + mark +
            frame(mapOf("type" to "meta", "stopReason" to "stop", "modelErrorCode" to 0)) + "data: [DONE]\n\n"
        val exchange = HttpExchange(id = "e", sessionId = "s", connectionId = "c", method = "POST",
            url = "https://api.yuanbao.tencent.com/api/chat/test", protocol = "HTTP/1.1", startedAt = 1,
            endedAt = 2, status = 200, completion = completion, requestBody = BodyRef("req"),
            responseBody = BodyRef("res", truncated = truncated))
        return YuanbaoDecoder().decode(exchange, BodyPreview("{\"prompt\":\"合成问题\"}", false, false),
            BodyPreview(stream, false, false))
    }

    @Test fun matchingAnnotationSnapshotDoesNotDuplicateOrRewriteText() {
        val result = read(mark())
        assertEquals("complete", result.status)
        assertEquals(answer, result.answer)
        assertEquals("yuanbao-chat-sse/2", result.decoder)
        assertTrue(result.searchSources.isEmpty())
        assertEquals("not_collected", result.referenceCaptureStatus)
    }

    @Test fun changedOrUnrecognizedAnnotationCannotSucceed() {
        val variants = listOf(mark(content = "别的回答[](@mark_underline=1)"), mark(extra = true),
            mark(id = 1.5), mark(id = "1"), mark(id = 0), mark(end = "[](@mark_underline=2)"),
            mark(id = 3, end = "[](@mark_underline=3)"),
            mark(content = "$visible[](@unknown=1)[](@mark_underline=1)"))
        for (variant in variants) {
            assertEquals("unsupported", read(variant).status)
            assertTrue(read(variant).diagnostics.contains("unrecognized_event"))
        }
    }

    @Test fun annotationNeverOverridesTransportCompleteness() {
        assertEquals("incomplete", read(mark(), truncated = true).status)
        assertEquals("incomplete", read(mark(), completion = Completion.INTERRUPTED).status)
    }
}
