package com.packetcapture.capture.engine

import com.packetcapture.capture.proxy.RawStreamBridge
import com.packetcapture.core.*
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.timeout.ReadTimeoutHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

class RawTransportTest {
    @Test fun optionalUdp443PolicyRejectsIpv4AndIpv6ButKeepsDnsAndTcpAvailable() = runBlocking {
        val fixture = Fixture()
        val recorder = TrafficRecorder(fixture.repository, fixture.bodies, CaptureConfig(), "s") {}
        try {
            val ordinary = ConnectionRegistry("s", { _, _, _, _, _ -> null to null }, recorder)
            assertTrue(ordinary.opened(1, "10.111.0.1", 4444, "192.0.2.1", 443, 17))
            ordinary.closed(1, null)
            val blocking = ConnectionRegistry("s", { _, _, _, _, _ -> null to null }, recorder, blockUdp443 = true)
            assertFalse(blocking.opened(2, "10.111.0.1", 4444, "192.0.2.1", 443, 17))
            assertFalse(blocking.opened(3, "fd00::1", 4444, "2001:db8::1", 443, 17))
            assertTrue(blocking.opened(4, "10.111.0.1", 4444, "192.0.2.1", 443, 6))
            assertTrue(blocking.opened(5, "10.111.0.1", 4444, "192.0.2.1", 53, 17))
            listOf(2L, 3L, 4L, 5L).forEach { blocking.closed(it, null) }
            withTimeout(6000) { while (fixture.connections.size < 5) delay(20) }
            assertTrue(fixture.connections.getValue("s-2").note!!.contains("已阻止 UDP 443"))
            assertEquals(Completion.FAILED, fixture.connections.getValue("s-3").completion)
            assertTrue(fixture.saved.value.values.none { it.connectionId == "s-2" || it.connectionId == "s-3" })
        } finally { recorder.close() }
    }
    @Test fun rawTcpRelaysIdenticalBytesPublishesBothDirectionsAndCommitsOnClose() = runBlocking {
        val fixture = Fixture()
        val recorder = TrafficRecorder(fixture.repository, fixture.bodies, CaptureConfig(), "s") {}
        val client = EmbeddedChannel(ReadTimeoutHandler(120))
        val remote = EmbeddedChannel(ReadTimeoutHandler(120))
        try {
            RawStreamBridge(client, remote, recorder.beginRaw(connection())).install()
            assertNull(client.pipeline().get(ReadTimeoutHandler::class.java))
            assertNull(remote.pipeline().get(ReadTimeoutHandler::class.java))
            val upload = byteArrayOf(0, 1, -1) + "请详细介绍中国历史".toByteArray()
            val download = "answer".toByteArray() + byteArrayOf(0, -128)
            val input = Unpooled.wrappedBuffer(byteArrayOf(99) + upload).apply { readByte() }
            client.writeInbound(input)
            val forwarded = remote.readOutbound<ByteBuf>()
            assertEquals(1, forwarded.readerIndex())
            assertArrayEquals(upload, ByteArray(forwarded.readableBytes()).also { forwarded.getBytes(forwarded.readerIndex(), it) })
            forwarded.release()
            remote.writeInbound(Unpooled.wrappedBuffer(download.copyOfRange(0, 3)))
            remote.writeInbound(Unpooled.wrappedBuffer(download.copyOfRange(3, download.size)))
            val live = withTimeout(6000) { fixture.saved.first { entries -> entries.values.any { it.responseBody?.savedBytes == download.size.toLong() } }.values.single() }
            assertTrue(live.rawTransport); assertEquals(Completion.ACTIVE, live.completion)
            assertTrue(live.requestBody!!.inProgress)
            assertArrayEquals(upload, fixture.bodies.read(live.requestBody!!))
            assertArrayEquals(download, fixture.bodies.read(live.responseBody!!))
            client.close(); remote.runPendingTasks()
            val final = withTimeout(6000) { fixture.saved.first { it.values.any { e -> e.completion == Completion.COMPLETE } }.values.single() }
            assertFalse(final.requestBody!!.truncated)
            assertFalse(final.responseBody!!.inProgress)
            assertArrayEquals(upload, fixture.bodies.read(final.requestBody!!))
        } finally { client.finishAndReleaseAll(); remote.finishAndReleaseAll(); recorder.close() }
    }

    @Test fun udpRegistryRecordsSeparatePcapDirectionsAndQuicHint() = runBlocking {
        val fixture = Fixture()
        val recorder = TrafficRecorder(fixture.repository, fixture.bodies, CaptureConfig(), "s") {}
        val registry = ConnectionRegistry("s", { _, _, _, _, _ -> 10 to "test.app" }, recorder)
        try {
            registry.opened(1, "10.111.0.1", 4444, "192.0.2.1", 443, 17)
            val initial = byteArrayOf(0xc0.toByte(), 0, 0, 0, 1, 2, 11, 12, 1, 13, 0)
            val outgoing = ByteArray(28) + initial
            val incoming = ByteArray(28) + byteArrayOf(1, 2, 3)
            registry.packet(1, true, outgoing, 28)
            registry.packet(1, false, incoming, 28)
            registry.closed(1, null)
            val final = withTimeout(6000) { fixture.saved.first { it.values.any { e -> e.completion == Completion.COMPLETE } }.values.single() }
            assertEquals("UDP", final.protocol)
            val up = fixture.bodies.read(final.requestBody!!)
            val down = fixture.bodies.read(final.responseBody!!)
            assertArrayEquals(RawPackets.header(), up.copyOfRange(0, 24))
            assertArrayEquals(outgoing, up.copyOfRange(40, up.size))
            assertArrayEquals(incoming, down.copyOfRange(40, down.size))
            withTimeout(6000) { while (fixture.connections.values.none { it.note?.contains("疑似 QUIC") == true }) delay(20) }
        } finally { recorder.close() }
    }

    private fun connection() = ConnectionRecord("s-1", "s", "10.111.0.1", 5555, "192.0.2.1", 8081, "TCP", packageName = "test.app")

    private class Fixture {
        val saved = MutableStateFlow<Map<String, HttpExchange>>(emptyMap())
        val connections = ConcurrentHashMap<String, ConnectionRecord>()
        val repository = object : CaptureRepository {
            override fun sessions(query: String) = flowOf(emptyList<CaptureSession>())
            override fun exchanges(sessionId: String?, filter: ExchangeFilter, limit: Int) = saved.map { it.values.toList() }
            override fun connections(sessionId: String?) = flowOf(emptyList<ConnectionRecord>())
            override fun exchange(id: String) = saved.map { it[id] }
            override suspend fun session(id: String): CaptureSession? = null
            override suspend fun connection(id: String) = connections[id]
            override suspend fun allConnections(sessionId: String) = emptyList<ConnectionRecord>()
            override suspend fun allExchanges(sessionId: String) = saved.value.values.toList()
            override suspend fun saveSession(session: CaptureSession) {}
            override suspend fun saveConnection(connection: ConnectionRecord) { connections[connection.id] = connection }
            override suspend fun saveExchange(exchange: HttpExchange) { saved.update { it + (exchange.id to exchange) } }
            override suspend fun deleteSession(id: String) {}
            override suspend fun clearStorage() {}
            override suspend fun recoverInterrupted() {}
        }
        val content = ConcurrentHashMap<String, ByteArrayOutputStream>()
        val bodies = object : BodyStore {
            override fun open(sessionId: String, exchangeId: String, part: BodyPart, limit: Long, totalLimit: Long): BodySink {
                val key = "$exchangeId-$part"
                val out = ByteArrayOutputStream().also { content[key] = it }
                return object : BodySink {
                    override fun append(bytes: ByteArray) = synchronized(out) { out.write(bytes) }
                    override suspend fun snapshot() = synchronized(out) { BodyRef(key, out.size().toLong(), out.size().toLong(), inProgress = true) }
                    override suspend fun finish() = snapshot().copy(inProgress = false)
                }
            }
            override suspend fun read(ref: BodyRef, maxBytes: Int) = content.getValue(ref.key).let { out -> synchronized(out) { out.toByteArray().copyOf(minOf(out.size(), ref.savedBytes.toInt(), maxBytes)) } }
            override suspend fun preview(ref: BodyRef?, headers: List<Header>) = BodyPreview("", false, false)
            override suspend fun deleteSession(sessionId: String) {}
            override suspend fun clearAll() {}
            override suspend fun usedBytes() = 0L
            override suspend fun recoverOrphans(liveSessionIds: Set<String>) {}
        }
    }
}
