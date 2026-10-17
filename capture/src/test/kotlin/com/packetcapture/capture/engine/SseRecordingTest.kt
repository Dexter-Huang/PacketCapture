package com.packetcapture.capture.engine

import com.packetcapture.capture.proxy.ExchangeBridge
import com.packetcapture.core.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.*
import io.netty.handler.timeout.ReadTimeoutHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class SseRecordingTest {
    @Test fun openEventStreamPublishesPrefixesAndFinishesWithoutClosingForwardingEarly() = runBlocking {
        val saved = MutableStateFlow<HttpExchange?>(null)
        val repository = object : CaptureRepository {
            override fun sessions(query: String) = flowOf(emptyList<CaptureSession>())
            override fun exchanges(sessionId: String?, filter: ExchangeFilter, limit: Int) = flowOf(emptyList<HttpExchange>())
            override fun connections(sessionId: String?) = flowOf(emptyList<ConnectionRecord>())
            override fun exchange(id: String) = saved
            override suspend fun session(id: String): CaptureSession? = null
            override suspend fun connection(id: String): ConnectionRecord? = null
            override suspend fun allConnections(sessionId: String) = emptyList<ConnectionRecord>()
            override suspend fun allExchanges(sessionId: String) = listOfNotNull(saved.value)
            override suspend fun saveSession(session: CaptureSession) {}
            override suspend fun saveConnection(connection: ConnectionRecord) {}
            override suspend fun saveExchange(exchange: HttpExchange) { saved.value = exchange }
            override suspend fun deleteSession(id: String) {}
            override suspend fun clearStorage() {}
            override suspend fun recoverInterrupted() {}
        }
        val bodies = object : BodyStore {
            val sinks = mutableMapOf<String, ByteArrayOutputStream>()
            override fun open(sessionId: String, exchangeId: String, part: BodyPart, limit: Long, totalLimit: Long): BodySink {
                val key = "$exchangeId-$part"
                val out = ByteArrayOutputStream().also { sinks[key] = it }
                return object : BodySink {
                    override fun append(bytes: ByteArray) = synchronized(out) { out.write(bytes) }
                    override suspend fun snapshot() = synchronized(out) { BodyRef(key, out.size().toLong(), out.size().toLong(), inProgress = true) }
                    override suspend fun finish() = snapshot().copy(inProgress = false)
                }
            }
            override suspend fun read(ref: BodyRef, maxBytes: Int) = sinks.getValue(ref.key).toByteArray().take(minOf(maxBytes, ref.savedBytes.toInt())).toByteArray()
            override suspend fun preview(ref: BodyRef?, headers: List<Header>) = BodyPreview("", false, false)
            override suspend fun deleteSession(sessionId: String) {}
            override suspend fun clearAll() {}
            override suspend fun usedBytes() = 0L
            override suspend fun recoverOrphans(liveSessionIds: Set<String>) {}
        }
        val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()
        val recorder = TrafficRecorder(repository, bodies, CaptureConfig(), "session", warnings::add)
        val client = EmbeddedChannel(ReadTimeoutHandler(120))
        val upstream = EmbeddedChannel(ReadTimeoutHandler(120))
        val connection = ConnectionRecord("c", "session", "127.0.0.1", 1234, "192.0.2.1", 443, "TCP", host = "sse.test", decrypted = true)
        try {
            ExchangeBridge(client, upstream, connection, recorder, true, "HTTP/1.1").install()
            client.writeInbound(DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/events").apply { headers().set("Host", "sse.test") })
            upstream.writeInbound(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK).apply {
                headers().set("Content-Type", "text/event-stream; charset=utf-8")
            })
            assertNull(client.pipeline().get(ReadTimeoutHandler::class.java))
            assertNull(upstream.pipeline().get(ReadTimeoutHandler::class.java))
            val first = "id: 1\ndata: 你好\n\n".toByteArray()
            // Split inside the first Chinese UTF-8 code point, as a real TCP stream may do.
            val split = "id: 1\ndata: ".toByteArray().size + 1
            upstream.writeInbound(DefaultHttpContent(Unpooled.wrappedBuffer(first.copyOfRange(0, split))))
            upstream.writeInbound(DefaultHttpContent(Unpooled.wrappedBuffer(first.copyOfRange(split, first.size))))
            val live = withTimeout(6000) { saved.filterNotNull().first { it.responseBody?.savedBytes == first.size.toLong() } }
            assertEquals(Completion.ACTIVE, live.completion)
            assertTrue(live.responseBody!!.inProgress)
            assertArrayEquals(first, bodies.read(live.responseBody!!))
            assertTrue(client.isActive && upstream.isActive)
            val tail = "data: [DONE]\n\n".toByteArray()
            upstream.writeInbound(DefaultLastHttpContent(Unpooled.wrappedBuffer(tail)))
            val final = withTimeout(6000) { saved.filterNotNull().first { it.completion == Completion.COMPLETE } }
            assertFalse(final.responseBody!!.inProgress)
            assertArrayEquals(first + tail, bodies.read(final.responseBody!!))
            assertEquals(listOf("你好", "[DONE]"), ServerSentEvents.parse(bodies.read(final.responseBody!!).toString(Charsets.UTF_8)).events.map { it.data })
            assertTrue(warnings.toString(), warnings.isEmpty())
        } finally {
            client.finishAndReleaseAll(); upstream.finishAndReleaseAll(); recorder.close()
        }
    }
}
