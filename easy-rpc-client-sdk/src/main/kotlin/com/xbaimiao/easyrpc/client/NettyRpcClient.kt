package com.xbaimiao.easyrpc.client

import com.xbaimiao.easyrpc.core.*
import com.xbaimiao.easyrpc.dsl.*
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.bytes.ByteArrayDecoder
import io.netty.handler.codec.bytes.ByteArrayEncoder
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Netty 版 RPC client。
 *
 * client 连接到中心 `NettyRpcServer` 后会发送 HELLO frame 注册自己的 [nodeId]。
 * 注册完成后：
 *
 * - 可以调用 service：`RpcTarget.service()`。
 * - 可以调用其它 client：`RpcTarget.node("client-b")`。
 * - 可以注册自己的 handler，被 service 或其它 client 调用。
 */
class NettyRpcClient(
    private val host: String,
    private val port: Int,
    val nodeId: String,
) : RpcCaller, RpcRuntime, AutoCloseable {
    /** 当前 client 的 endpoint。handler 和 pending 请求都存在这里。 */
    override val endpoint = RpcEndpoint(nodeId, RpcNodeKind.CLIENT)
    private val group: EventLoopGroup = NioEventLoopGroup()
    private val running = AtomicBoolean(false)
    private lateinit var channel: Channel

    private val connection = object : RpcConnection {
        override val remoteName: String = "router"
        override fun send(packet: ByteArray) {
            channel.writeAndFlush(packet)
        }

        override fun close() {
            runCatching { channel.close() }
        }
    }

    /** 建立 Netty 连接并发送 HELLO 注册包。 */
    fun connect(): NettyRpcClient {
        check(running.compareAndSet(false, true)) { "RPC client already connected" }
        val bootstrap = Bootstrap()
            .group(group)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast(LengthFieldBasedFrameDecoder(16 * 1024 * 1024, 0, 4, 0, 4))
                        .addLast(LengthFieldPrepender(4))
                        .addLast(ByteArrayDecoder())
                        .addLast(ByteArrayEncoder())
                        .addLast(ClientHandler())
                }
            })
        channel = bootstrap.connect(InetSocketAddress(host, port)).syncUninterruptibly().channel()
        sendHello()
        return this
    }

    /** 发送单响应 RPC。 */
    override fun <A, R> call(
        method: RpcMethod<A, R>,
        args: A,
        target: RpcTarget,
        timeout: Duration,
    ): CompletableFuture<R> {
        check(running.get()) { "RPC client is not connected" }
        return endpoint.call(connection, method, args, target, timeout)
    }

    /** 发送多响应 RPC。 */
    override fun <A, R> callAll(
        method: RpcMethod<A, R>,
        args: A,
        target: RpcTarget,
        collectFor: Duration,
    ): CompletableFuture<List<R>> {
        check(running.get()) { "RPC client is not connected" }
        return endpoint.callMany(connection, method, args, target, collectFor)
    }

    /** 便捷 listen 方法；也可以直接使用 `RpcMethod.listen(client) { ... }`。 */
    fun <R> listen(method: RpcMethod0<R>, handler: () -> R) = method.listen(endpoint, handler)
    fun <A, R> listen(method: RpcMethod1<A, R>, handler: (A) -> R) = method.listen(endpoint, handler)
    fun <A, B, R> listen(method: RpcMethod2<A, B, R>, handler: (A, B) -> R) = method.listen(endpoint, handler)
    fun <A, B, C, R> listen(method: RpcMethod3<A, B, C, R>, handler: (A, B, C) -> R) = method.listen(endpoint, handler)
    fun <A, B, C, D, R> listen(method: RpcMethod4<A, B, C, D, R>, handler: (A, B, C, D) -> R) = method.listen(endpoint, handler)
    fun <A, B, C, D, E, R> listen(method: RpcMethod5<A, B, C, D, E, R>, handler: (A, B, C, D, E) -> R) = method.listen(endpoint, handler)

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { channel.close() }
        endpoint.close()
        group.shutdownGracefully()
    }

    private fun sendHello() {
        val hello = RpcFrame(
            type = RpcFrameType.HELLO,
            requestId = 0,
            sourceNode = nodeId,
            sourceKind = RpcNodeKind.CLIENT,
            target = RpcTarget.Service,
        )
        channel.writeAndFlush(RpcFrameCodec.encode(hello))
    }

    private inner class ClientHandler : SimpleChannelInboundHandler<ByteArray>() {
        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteArray) {
            endpoint.receive(connection, msg)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            close()
        }
    }
}
