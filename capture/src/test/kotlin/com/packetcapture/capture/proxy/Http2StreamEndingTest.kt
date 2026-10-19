package com.packetcapture.capture.proxy

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http2.*
import io.netty.util.ReferenceCountUtil
import org.junit.Assert.*
import org.junit.Test

class Http2StreamEndingTest {
    @Test fun emptyEndStreamDataBecomesLastHttpContent() {
        val channel = EmbeddedChannel(Http2StreamFrameToHttpObjectCodec(false))
        try {
            channel.writeInbound(DefaultHttp2HeadersFrame(DefaultHttp2Headers().status("200").set("content-type", "text/event-stream"), false))
            assertTrue(channel.readInbound<Any>() is HttpResponse)
            val data = "data: {\"finish\":1}\n\n".toByteArray()
            channel.writeInbound(DefaultHttp2DataFrame(Unpooled.wrappedBuffer(data), false))
            val content = channel.readInbound<HttpContent>()
            assertFalse(content is LastHttpContent)
            assertEquals(data.size, content.content().readableBytes())
            ReferenceCountUtil.release(content)
            channel.writeInbound(DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, true))
            val end = channel.readInbound<Any>()
            assertTrue(end is LastHttpContent)
            ReferenceCountUtil.release(end)
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun protocolFinishAndResetDoNotBecomeHttpEndOfStream() {
        val channel = EmbeddedChannel(Http2StreamFrameToHttpObjectCodec(false))
        try {
            channel.writeInbound(DefaultHttp2HeadersFrame(DefaultHttp2Headers().status("200"), false))
            ReferenceCountUtil.release(channel.readInbound<Any>())
            channel.writeInbound(DefaultHttp2DataFrame(Unpooled.copiedBuffer("data: {\"finish\":1}\n\n", Charsets.UTF_8), false))
            val content = channel.readInbound<Any>()
            assertTrue(content is HttpContent)
            assertFalse(content is LastHttpContent)
            ReferenceCountUtil.release(content)
            channel.writeInbound(DefaultHttp2ResetFrame(Http2Error.CANCEL))
            val reset = channel.readInbound<Any>()
            assertTrue(reset is Http2ResetFrame)
            assertFalse(reset is LastHttpContent)
            ReferenceCountUtil.release(reset)
            assertNull(channel.readInbound<Any>())
        } finally { channel.finishAndReleaseAll() }
    }
}
