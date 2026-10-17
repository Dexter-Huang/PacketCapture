package com.packetcapture.capture.proxy

import java.nio.channels.ClosedChannelException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class ProxyFailureDescriptionTest {
    @Test fun closedChannelKeepsItsTypeWithoutClaimingCertificateRejection() {
        assertEquals("ClosedChannelException", describeProxyFailure(ClosedChannelException()))
    }

    @Test fun handshakeFailureKeepsTheUnderlyingCertificateReason() {
        val error = SSLHandshakeException("handshake failed").apply {
            initCause(java.security.cert.CertificateException("unknown issuer"))
        }
        assertEquals("SSLHandshakeException: handshake failed → CertificateException: unknown issuer", describeProxyFailure(error))
    }
}
