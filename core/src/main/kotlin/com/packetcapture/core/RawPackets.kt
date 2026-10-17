package com.packetcapture.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Classic PCAP with LINKTYPE_RAW (101). Each direction is a separate valid capture. */
object RawPackets {
    data class QuicHint(val version: String, val host: String? = null)
    fun header(): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0xa1b2c3d4.toInt()).putShort(2).putShort(4).putInt(0).putInt(0)
        .putInt(65535).putInt(101).array()

    fun record(packet: ByteArray, timestampMillis: Long = System.currentTimeMillis()): ByteArray {
        require(packet.size <= 65535)
        return ByteBuffer.allocate(16 + packet.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((timestampMillis / 1000).toInt()).putInt(((timestampMillis % 1000) * 1000).toInt())
            .putInt(packet.size).putInt(packet.size).put(packet).array()
    }

    /** Hints from public headers only; never decrypts or interprets application data. */
    fun quicHint(payload: ByteArray): QuicHint? {
        // Legacy gQUIC public flags: version-present + 8-byte connection ID.
        if (payload.size >= 14 && payload[0].toInt() and 0xcb == 0x09 && payload[9] == 'Q'.code.toByte()) {
            val version = String(payload, 9, 4, Charsets.US_ASCII)
            if (version in setOf("Q039", "Q043", "Q046")) return QuicHint(version, legacyHost(payload))
        }
        if (payload.size < 7 || payload[0].toInt() and 0xc0 != 0xc0) return null
        val version = ByteBuffer.wrap(payload, 1, 4).int
        if (version != 1 && version != 0x6b3343cf) return null
        val destinationLength = payload[5].toInt() and 0xff
        if (destinationLength > 20 || payload.size <= 6 + destinationLength) return null
        val sourceLength = payload[6 + destinationLength].toInt() and 0xff
        return if (sourceLength <= 20 && payload.size > 7 + destinationLength + sourceLength)
            QuicHint(if (version == 1) "v1" else "v2") else null
    }

    fun possibleQuic(payload: ByteArray): Boolean = quicHint(payload) != null

    private fun legacyHost(payload: ByteArray): String? {
        val start = (14 until minOf(128, payload.size - 7)).firstOrNull { offset ->
            payload[offset] == 67.toByte() && payload[offset + 1] == 72.toByte() &&
                payload[offset + 2] == 76.toByte() && payload[offset + 3] == 79.toByte()
        } ?: return null
        val message = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val count = message.getShort(start + 4).toInt() and 0xffff
        if (count !in 1..64) return null
        val dataStart = start + 8 + count * 8
        if (dataStart > payload.size) return null
        var previous = 0
        for (i in 0 until count) {
            val entry = start + 8 + i * 8
            val end = message.getInt(entry + 4)
            if (end < previous || end > payload.size - dataStart) return null
            if (payload[entry] == 83.toByte() && payload[entry + 1] == 78.toByte() &&
                payload[entry + 2] == 73.toByte() && payload[entry + 3] == 0.toByte()) {
                val host = String(payload, dataStart + previous, end - previous, Charsets.US_ASCII)
                return host.takeIf { it.length in 1..253 && it.matches(Regex("[A-Za-z0-9.-]+")) }
            }
            previous = end
        }
        return null
    }
}
