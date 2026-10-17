package com.packetcapture.core

import org.junit.Assert.*
import org.junit.Test

class TlsHostOverridesTest {
    @Test fun explicitNamesRoundTrip() {
        val values = TlsHostOverrides.parse("162.14.20.38 = API.YUANBAO.TENCENT.COM\n\n2001:db8::1=example.org")
        assertEquals("api.yuanbao.tencent.com", values["162.14.20.38"])
        assertEquals(values, TlsHostOverrides.parse(TlsHostOverrides.format(values)))
        assertTrue(TlsHostOverrides.parse("").isEmpty())
    }
    @Test fun rejectsAmbiguousOrInvalidPeerNames() {
        listOf("a.com=b.com\na.com=c.com", "a.com=1.2.3.4", "a.com=*.example.org", "a.com=https://b.com", "a.com=b.com=extra", "a.com=-bad.com").forEach {
            assertThrows(IllegalArgumentException::class.java) { TlsHostOverrides.parse(it) }
        }
    }
}
