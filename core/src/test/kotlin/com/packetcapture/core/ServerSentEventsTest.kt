package com.packetcapture.core

import org.junit.Assert.*
import org.junit.Test

class ServerSentEventsTest {
    @Test fun supportsBomMixedLineEndingsCommentsAndMultilineData() {
        val result = ServerSentEvents.parse("\uFEFF: keepalive\r\nid: 7\revent: delta\ndata: 你好\r\ndata: world\r\n\r\ndata: [DONE]\n\n")
        assertEquals(listOf(SseEvent(1, "delta", "7", "你好\nworld"), SseEvent(2, "message", "7", "[DONE]")), result.events)
        assertEquals(1, result.comments)
        assertFalse(result.pending)
    }
    @Test fun partialEventIsNotDispatchedUntilBlankLineArrives() {
        val prefix = "data: {\"delta\":\"你"
        assertTrue(ServerSentEvents.parse(prefix).events.isEmpty())
        assertTrue(ServerSentEvents.parse(prefix).pending)
        assertTrue(ServerSentEvents.parse(prefix + "好\"}\n").events.isEmpty())
        assertEquals("{\"delta\":\"你好\"}", ServerSentEvents.parse(prefix + "好\"}\n\n").events.single().data)
    }
    @Test fun honorsEmptyDataPersistentIdAndRetryRules() {
        val result = ServerSentEvents.parse("id: first\nretry: 1000\n\nevent: ignored\n\nid: bad\u0000id\nretry: +1\ndata:\n\nid:\nretry: 99999999999999999999999\ndata: next\n\n")
        assertEquals(1000L, result.retryMillis)
        assertEquals(listOf(SseEvent(1, "message", "first", ""), SseEvent(2, "message", "", "next")), result.events)
    }
    @Test fun boundsEventWindowWithoutLosingOriginalEventNumbers() {
        val result = ServerSentEvents.parse((1..1000).joinToString("") { "data: $it\n\n" }, maxEvents = 3)
        assertEquals(1000, result.totalEvents)
        assertEquals(listOf(998, 999, 1000), result.events.map { it.number })
        assertEquals(listOf("998", "999", "1000"), result.events.map { it.data })
    }
    @Test fun recognizesMimeTypeCaseAndParameters() {
        assertTrue(listOf(Header("Content-Type", "Text/Event-Stream; charset=utf-8")).isEventStream())
        assertFalse(listOf(Header("Content-Type", "text/event-stream-other")).isEventStream())
    }
}
