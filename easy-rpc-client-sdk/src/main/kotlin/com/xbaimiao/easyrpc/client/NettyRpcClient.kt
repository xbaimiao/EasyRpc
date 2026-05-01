package com.xbaimiao.easyrpc.client

import com.xbaimiao.easyrpc.core.*
import com.xbaimiao.easyrpc.dsl.*
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Netty 版 RPC client。
 *
 * client 连接到中心 `NettyRpcServer` 后会发送 HELLO frame 注册自己的 [nodeId]。
 * 注册完成后：
 *
 * - 可以调用 service：`RpcTarget.service()`。
 * - 可以调用其它 client：`RpcTarget.node("client-b")`。
 * - 可以调用带指定 tag 的所有 client：`RpcTarget.tag("lobby")`。
 * - 可以注册自己的 handler，被 service 或其它 client 调用。
 * - 可以通过 [onlineClients] 或 [onlineClientsByTag] 查询 service 同步的在线 client。
 *
 * 只有手动调用 [close] 才会彻底释放资源；网络断线会保留 endpoint 和 listen handler，
 * 并按 1s、2s、4s ... 最大 30s 的退避间隔自动重连。
 */
class NettyRpcClient(
    private val host: String,
    private val port: Int,
    val nodeId: String,
    private val reconnectInitialDelay: Duration = Duration.ofSeconds(1),
    private val reconnectMaxDelay: Duration = Duration.ofSeconds(30),
    tags: Collection<String> = emptySet(),
) : RpcCaller, RpcRuntime, AutoCloseable {
    /** 当前 client 的 endpoint。handler 和 pending 请求都存在这里。 */
    override val endpoint = RpcEndpoint(nodeId, RpcNodeKind.CLIENT, nodeTags = tags.normalizedTags())
    val tags: Set<String> = tags.normalizedTags()
    private val group: EventLoopGroup = NioEventLoopGroup()
    private val running = AtomicBoolean(false)
    private val manuallyClosed = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val reconnectScheduled = AtomicBoolean(false)
    private val onlineClients = ConcurrentHashMap<String, RpcClientInfo>()
    private lateinit var bootstrap: Bootstrap
    @Volatile private var channel: Channel? = null
    @Volatile private var reconnectDelayMillis = reconnectInitialDelay.toMillis().coerceAtLeast(1)

    private val connection = object : RpcConnection {
        override val remoteName: String = "router"
        override fun send(packet: ByteArray) {
            val activeChannel = channel
            if (activeChannel == null || !activeChannel.isActive) {
                throw RpcException("disconnected", "RPC client is disconnected: $nodeId")
            }
            activeChannel.writeAndFlush(packet)
        }

        override fun close() {
            runCatching { channel?.close() }
        }
    }

    /** 启动 Netty client，并尝试建立连接；如果 service 暂不可用，会进入后台重连。 */
    fun connect(): NettyRpcClient {
        check(running.compareAndSet(false, true)) { "RPC client already connected" }
        manuallyClosed.set(false)
        bootstrap = Bootstrap()
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
        val connectFuture = bootstrap.connect(InetSocketAddress(host, port))
        connectFuture.awaitUninterruptibly()
        if (connectFuture.isSuccess) {
            activateChannel(connectFuture.channel())
        } else {
            scheduleReconnect()
        }
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
        check(isConnected()) { "RPC client is reconnecting: $nodeId" }
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
        check(isConnected()) { "RPC client is reconnecting: $nodeId" }
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
        manuallyClosed.set(true)
        if (!running.compareAndSet(true, false)) return
        reconnectScheduled.set(false)
        connecting.set(false)
        val oldChannel = channel
        channel = null
        runCatching { oldChannel?.close() }
        endpoint.close()
        group.shutdownGracefully()
    }

    /** 当前是否有可用连接。重连窗口内会返回 false。 */
    fun isConnected(): Boolean = channel?.isActive == true

    /** service 同步过来的在线 client 列表。返回值是快照。 */
    fun onlineClients(): List<RpcClientInfo> = onlineClients.values
        .map { it.copy(tags = it.tags.toSet()) }
        .sortedBy { it.nodeId }

    /** 获取指定 nodeId 的在线 client。 */
    fun onlineClient(nodeId: String): RpcClientInfo? = onlineClients[nodeId]
        ?.let { it.copy(tags = it.tags.toSet()) }

    /** 获取拥有指定 tag 的所有在线 client。 */
    fun onlineClientsByTag(tag: String): List<RpcClientInfo> = onlineClients()
        .filter { tag in it.tags }

    private fun activateChannel(connected: Channel) {
        channel = connected
        reconnectDelayMillis = reconnectInitialDelay.toMillis().coerceAtLeast(1)
        sendHello(connected)
    }

    private fun sendHello(activeChannel: Channel) {
        val hello = RpcFrame(
            type = RpcFrameType.HELLO,
            requestId = 0,
            sourceNode = nodeId,
            sourceKind = RpcNodeKind.CLIENT,
            target = RpcTarget.Service,
            sourceTags = tags,
        )
        activeChannel.writeAndFlush(RpcFrameCodec.encode(hello))
    }

    private fun applyClientsSync(frame: RpcFrame) {
        onlineClients.clear()
        frame.onlineClients.forEach { info ->
            onlineClients[info.nodeId] = info.copy(tags = info.tags.toSet())
        }
    }

    private fun scheduleReconnect() {
        if (!running.get() || manuallyClosed.get()) return
        if (!reconnectScheduled.compareAndSet(false, true)) return

        val delay = reconnectDelayMillis
        reconnectDelayMillis = min(reconnectDelayMillis * 2, reconnectMaxDelay.toMillis().coerceAtLeast(delay))
        group.next().schedule({
            reconnectScheduled.set(false)
            if (!running.get() || manuallyClosed.get() || isConnected()) return@schedule
            connectAsync()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun connectAsync() {
        if (!connecting.compareAndSet(false, true)) return
        bootstrap.connect(InetSocketAddress(host, port)).addListener { future ->
            connecting.set(false)
            val connectFuture = future as ChannelFuture
            if (!running.get() || manuallyClosed.get()) {
                runCatching { connectFuture.channel().close() }
                return@addListener
            }
            if (connectFuture.isSuccess) {
                activateChannel(connectFuture.channel())
            } else {
                scheduleReconnect()
            }
        }
    }

    private inner class ClientHandler : SimpleChannelInboundHandler<ByteArray>() {
        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteArray) {
            val frame = runCatching { RpcFrameCodec.decode(msg) }.getOrElse { return }
            if (frame.type == RpcFrameType.CLIENTS_SYNC) {
                applyClientsSync(frame)
                return
            }
            endpoint.receive(connection, msg)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            val inactiveChannelIsCurrent = channel == ctx.channel()
            if (inactiveChannelIsCurrent) {
                channel = null
            }
            if (!inactiveChannelIsCurrent || !running.get() || manuallyClosed.get()) return
            onlineClients.clear()
            endpoint.failPending(RpcException("disconnected", "RPC client disconnected: $nodeId"))
            scheduleReconnect()
        }
    }
}

private fun Collection<String>.normalizedTags(): Set<String> = asSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .toSet()
