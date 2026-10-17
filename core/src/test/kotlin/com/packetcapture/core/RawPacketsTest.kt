package com.packetcapture.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RawPacketsTest {
    @Test fun legacyQ043HintExtractsPublicClientHelloDomainAndRejectsMalformedTables() {
        val host = "api.yuanbao.tencent.com".toByteArray()
        val message = ByteBuffer.allocate(16 + host.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("CHLO".toByteArray()).putShort(1).putShort(0).put(byteArrayOf(83, 78, 73, 0))
            .putInt(host.size).put(host).array()
        val prefix = byteArrayOf(0x0d) + ByteArray(8) + "Q043".toByteArray() + byteArrayOf(1) + ByteArray(16)
        val hint = RawPackets.quicHint(prefix + message)
        assertEquals("Q043", hint?.version)
        assertEquals("api.yuanbao.tencent.com", hint?.host)
        assertNull(RawPackets.quicHint(prefix.copyOf().apply { this[0] = 0x0c } + message))
        assertNull(RawPackets.quicHint(prefix.copyOf().apply { this[12] = '9'.code.toByte() } + message))
        assertNull(RawPackets.quicHint(prefix + message.copyOfRange(0, 15))?.host)
        assertNull(RawPackets.quicHint(prefix + message.copyOf().apply { this[12] = -1 })?.host)
    }
    @Test fun pcapPreservesDatagramBoundariesTimestampsAndIpBytes() {
        val first = byteArrayOf(0x45, 0, 0, 4)
        val second = byteArrayOf(0x60, 0, 0, 0, -1)
        val file = ByteBuffer.wrap(RawPackets.header() + RawPackets.record(first, 1234567) + RawPackets.record(second, 2000000))
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xa1b2c3d4.toInt(), file.int)
        assertEquals(2, file.short.toInt()); assertEquals(4, file.short.toInt())
        file.position(20); assertEquals(101, file.int)
        assertEquals(1234, file.int); assertEquals(567000, file.int)
        assertEquals(first.size, file.int); assertEquals(first.size, file.int)
        assertArrayEquals(first, ByteArray(first.size).also { file.get(it) })
        assertEquals(2000, file.int); assertEquals(0, file.int)
        assertEquals(second.size, file.int); assertEquals(second.size, file.int)
        assertArrayEquals(second, ByteArray(second.size).also { file.get(it) })
        assertFalse(file.hasRemaining())
    }

    @Test fun quicHintRequiresKnownVersionAndPlausibleConnectionIds() {
        val initial = byteArrayOf(0xc0.toByte(), 0, 0, 0, 1, 2, 11, 12, 1, 13, 0)
        assertTrue(RawPackets.possibleQuic(initial))
        assertTrue(RawPackets.possibleQuic(initial.copyOf().apply { this[1] = 0x6b; this[2] = 0x33; this[3] = 0x43; this[4] = 0xcf.toByte() }))
        assertFalse(RawPackets.possibleQuic(initial.copyOfRange(0, 8)))
        assertFalse(RawPackets.possibleQuic(initial.copyOf().apply { this[5] = 21 }))
        assertFalse(RawPackets.possibleQuic(initial.copyOf().apply { this[4] = 8 }))
        assertFalse(RawPackets.possibleQuic(byteArrayOf(0x12, 0x34, 0x01, 0x00)))
    }
}
