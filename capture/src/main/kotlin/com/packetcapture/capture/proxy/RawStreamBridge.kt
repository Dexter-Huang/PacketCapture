package com.packetcapture.capture.proxy

import com.packetcapture.capture.engine.TrafficRecorder
import com.packetcapture.core.BodyPart
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.util.ReferenceCountUtil

/** Copy the readable bytes before forwarding the same message with its original ownership. */
internal class RawStreamBridge(
    private val client: Channel, private val remote: Channel,
    private val recording: TrafficRecorder.ExchangeRecording,
) {
    fun install(after: String? = null) {
        listOf(client, remote).forEach { channel ->
            channel.pipeline().get(ReadTimeoutHandler::class.java)?.let { channel.pipeline().remove(it) }
        }
        if (after == null) client.pipeline().addLast(Relay(remote, BodyPart.REQUEST))
        else client.pipeline().addAfter(after, "raw-client", Relay(remote, BodyPart.REQUEST))
        remote.pipeline().addLast(Relay(client, BodyPart.RESPONSE))
    }

    private inner class Relay(private val peer: Channel, private val part: BodyPart) : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is ByteBuf) recording.body(msg, part)
            if (peer.isActive) peer.writeAndFlush(msg).addListener(ChannelFutureListener {
                if (!it.isSuccess) {
                    recording.finish("传输写入失败: ${it.cause()?.message}")
                    ctx.close()
                }
            }) else {
                ReferenceCountUtil.release(msg)
                recording.finish("对端连接已关闭")
                ctx.close()
            }
        }
        override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
            if (peer.isActive) peer.config().isAutoRead = ctx.channel().isWritable
            ctx.fireChannelWritabilityChanged()
        }
        override fun channelInactive(ctx: ChannelHandlerContext) {
            recording.finish()
            peer.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
            ctx.fireChannelInactive()
        }
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            recording.finish("传输中断: ${cause.message}")
            ctx.close(); peer.close()
        }
    }
}
