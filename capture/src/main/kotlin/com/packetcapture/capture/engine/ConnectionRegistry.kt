package com.packetcapture.capture.engine

import com.packetcapture.core.*
import java.util.concurrent.ConcurrentHashMap

/** 原始五元组与回环 socket 的端口绑定，端口不能单独作为持久化主键，关闭连接时移除映射。 */
internal class ConnectionRegistry(private val sessionId: String, private val owner: (String, Int, String, Int, Int) -> Pair<Int?, String?>,
    private val recorder: TrafficRecorder, private val proxied: Boolean = false, private val blockUdp443: Boolean = false) {
    private val native = ConcurrentHashMap<Long, ConnectionRecord>()
    private val ports = ConcurrentHashMap<Int, Long>()
    private val datagrams = ConcurrentHashMap<Long, TrafficRecorder.ExchangeRecording>()
    fun opened(id: Long, source: String, sourcePort: Int, target: String, targetPort: Int, protocol: Int): Boolean {
        val (uid, pkg) = owner(source, sourcePort, target, targetPort, protocol)
        val connection = ConnectionRecord("$sessionId-$id", sessionId, source, sourcePort, target, targetPort,
            if (protocol == 6) "TCP" else if (protocol == 17) "UDP" else "IP/$protocol", uid, pkg,
            note = if (protocol == 17) (if (proxied) "UDP 经 SOCKS5 转发；保存双向 PCAP，尚未解密" else "UDP 保存双向 PCAP，尚未解密") else null)
        native[id] = connection
        recorder.connection(connection)
        if (blockUdp443 && protocol == 17 && targetPort == 443) {
            failed(id, "测试设置已阻止 UDP 443；等待应用尝试 HTTPS/TCP 回退")
            return false
        }
        if (protocol == 17) datagrams[id] = recorder.beginRaw(connection)
        return true
    }
    fun packet(id: Long, upload: Boolean, packet: ByteArray, payloadOffset: Int) {
        val recording = datagrams[id] ?: return
        recording.bytes(RawPackets.record(packet), if (upload) BodyPart.REQUEST else BodyPart.RESPONSE)
        val hint = if (payloadOffset in 0 until packet.size)
            RawPackets.quicHint(packet.copyOfRange(payloadOffset, minOf(packet.size, payloadOffset + 2048))) else null
        if (hint != null) {
            native.computeIfPresent(id) { _, old ->
                old.copy(host = hint.host ?: old.host,
                    note = "疑似 ${if (hint.version.startsWith('Q')) "Google QUIC" else "QUIC"} ${hint.version}；已保存双向 UDP PCAP，应用数据未解密")
            }?.let(recorder::connection)
        }
    }
    fun mapped(id: Long, port: Int) { if (native.containsKey(id)) ports[port] = id }
    fun forPort(port: Int): ConnectionRecord? = ports[port]?.let(native::get)
    fun failed(id: Long, message: String) {
        native.computeIfPresent(id) { _, old -> old.copy(note = message, completion = Completion.FAILED) }?.let(recorder::connection)
    }
    fun update(connection: ConnectionRecord) {
        val id = connection.id.substringAfterLast('-').toLongOrNull()
        if (id != null) native.computeIfPresent(id) { _, old ->
            // 原生侧可能先观察到 FIN；协议层补充元数据时不能把已结束连接恢复成 ACTIVE。
            if (old.endedAt != null) connection.copy(endedAt = old.endedAt, completion = old.completion) else connection
        }?.let(recorder::connection)
    }
    fun closed(id: Long, error: String?) {
        datagrams.remove(id)?.finish(error)
        native.remove(id)?.let { recorder.connection(it.copy(endedAt = System.currentTimeMillis(),
            completion = if (error != null || it.completion == Completion.FAILED) Completion.FAILED else Completion.COMPLETE, note = it.note ?: error)) }
        ports.entries.removeAll { it.value == id }
    }
}
