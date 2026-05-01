package com.xbaimiao.easyrpc.service

import com.xbaimiao.easyrpc.core.*
import com.xbaimiao.easyrpc.dsl.*
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.bytes.ByteArrayDecoder
import io.netty.handler.codec.bytes.ByteArrayEncoder
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Netty 版 RPC service。
 *
 * 它同时扮演两个角色：
 *
 * - service 节点：可以自己注册 RPC handler。
 * - router：负责把 client 发来的 frame 转发到 service、指定 client、all 或 allClients。
 * - registry：保存在线 client 的 nodeId/tags，并同步给所有 client。
 */
class NettyRpcServer(
    private val host: String = "0.0.0.0",
    private val port: Int = 29090,
    nodeId: String = "service",
) : RpcCaller, RpcRuntime, AutoCloseable {
    /** service 自己的 endpoint。发给 RpcTarget.service() 的请求会进入这里。 */
    override val endpoint = RpcEndpoint(nodeId, RpcNodeKind.SERVICE)
    private val bossGroup: EventLoopGroup = NioEventLoopGroup(1)
    private val workerGroup: EventLoopGroup = NioEventLoopGroup()
    private val clients = ConcurrentHashMap<String, ClientConnection>()
    private var serverChannel: Channel? = null

    private val routerConnection = object : RpcConnection {
        override val remoteName: String = "router"
        override fun send(packet: ByteArray) = route(RpcFrameCodec.decode(packet), null)
        override fun close() = Unit
    }

    /** 启动 Netty server 并开始接受 client 连接。 */
    fun start(): NettyRpcServer {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast(LengthFieldBasedFrameDecoder(16 * 1024 * 1024, 0, 4, 0, 4))
                        .addLast(LengthFieldPrepender(4))
                        .addLast(ByteArrayDecoder())
                        .addLast(ByteArrayEncoder())
                        .addLast(ServerHandler())
                }
            })
        serverChannel = bootstrap.bind(InetSocketAddress(host, port)).syncUninterruptibly().channel()
        return this
    }

    /** 阻塞等待 service 关闭。普通 main 方法可以用它保持进程不退出。 */
    fun awaitClose() {
        serverChannel?.closeFuture()?.syncUninterruptibly()
    }

    /** 当前在线 client 列表。返回值是快照，不会随内部状态继续变化。 */
    fun onlineClients(): List<RpcClientInfo> = clients.values
        .map { it.info.copy(tags = it.info.tags.toSet()) }
        .sortedBy { it.nodeId }

    /** 获取指定 nodeId 的在线 client。 */
    fun onlineClient(nodeId: String): RpcClientInfo? = clients[nodeId]?.info
        ?.let { it.copy(tags = it.tags.toSet()) }

    /** 获取拥有指定 tag 的所有在线 client。 */
    fun onlineClientsByTag(tag: String): List<RpcClientInfo> = onlineClients()
        .filter { tag in it.tags }

    /** service 主动向其它目标发起单响应 RPC。 */
    override fun <A, R> call(
        method: RpcMethod<A, R>,
        args: A,
        target: RpcTarget,
        timeout: Duration,
    ): CompletableFuture<R> = endpoint.call(routerConnection, method, args, target, timeout)

    /** service 主动向 all/allClients 发起多响应 RPC。 */
    override fun <A, R> callAll(
        method: RpcMethod<A, R>,
        args: A,
        target: RpcTarget,
        collectFor: Duration,
    ): CompletableFuture<List<R>> = endpoint.callMany(routerConnection, method, args, target, collectFor)

    /** 便捷 listen 方法；也可以直接使用 `RpcMethod.listen(server) { ... }`。 */
    fun <R> listen(method: RpcMethod0<R>, handler: () -> R) = method.listen(endpoint, handler)
    fun <A, R> listen(method: RpcMethod1<A, R>, handler: (A) -> R) = method.listen(endpoint, handler)
    fun <A, B, R> listen(method: RpcMethod2<A, B, R>, handler: (A, B) -> R) = method.listen(endpoint, handler)
    fun <A, B, C, R> listen(method: RpcMethod3<A, B, C, R>, handler: (A, B, C) -> R) = method.listen(endpoint, handler)
    fun <A, B, C, D, R> listen(method: RpcMethod4<A, B, C, D, R>, handler: (A, B, C, D) -> R) = method.listen(endpoint, handler)
    fun <A, B, C, D, E, R> listen(method: RpcMethod5<A, B, C, D, E, R>, handler: (A, B, C, D, E) -> R) = method.listen(endpoint, handler)

    override fun close() {
        runCatching { serverChannel?.close()?.syncUninterruptibly() }
        clients.values.forEach { runCatching { it.channel.close() } }
        clients.clear()
        endpoint.close()
        bossGroup.shutdownGracefully()
        workerGroup.shutdownGracefully()
    }

    private fun route(frame: RpcFrame, inbound: Channel?) {
        when (val target = frame.target) {
            RpcTarget.Service -> deliverToService(frame)
            is RpcTarget.Node -> deliverToNode(frame, target.nodeId, inbound)
            RpcTarget.All -> {
                deliverToService(frame)
                clients.values.forEach { deliverToChannel(frame, it.channel) }
            }
            RpcTarget.AllClients -> clients.values.forEach { deliverToChannel(frame, it.channel) }
            is RpcTarget.Tag -> clients.values
                .filter { target.tag in it.info.tags }
                .forEach { deliverToChannel(frame, it.channel) }
        }
    }

    private fun deliverToNode(frame: RpcFrame, nodeId: String, inbound: Channel?) {
        if (nodeId == endpoint.nodeId) {
            deliverToService(frame)
            return
        }
        val client = clients[nodeId]
        if (client == null || !client.channel.isActive) {
            sendRouteError(frame, inbound, "target_not_found", "RPC target node not found: $nodeId")
            return
        }
        deliverToChannel(frame, client.channel)
    }

    private fun deliverToService(frame: RpcFrame) {
        endpoint.receive(routerConnection, RpcFrameCodec.encode(frame))
    }

    private fun deliverToChannel(frame: RpcFrame, channel: Channel) {
        channel.writeAndFlush(RpcFrameCodec.encode(frame))
    }

    private fun sendRouteError(request: RpcFrame, inbound: Channel?, classifier: String, message: String) {
        val error = RpcFrame(
            type = RpcFrameType.ERROR,
            requestId = request.requestId,
            sourceNode = endpoint.nodeId,
            sourceKind = RpcNodeKind.SERVICE,
            target = RpcTarget.Node(request.sourceNode),
            group = request.group,
            method = request.method,
            errorClassifier = classifier,
            errorMessage = message,
        )
        if (request.sourceNode == endpoint.nodeId) {
            deliverToService(error)
        } else {
            val source = clients[request.sourceNode]?.channel ?: inbound
            source?.writeAndFlush(RpcFrameCodec.encode(error))
        }
    }

    private fun broadcastClientSync() {
        val sync = RpcFrame(
            type = RpcFrameType.CLIENTS_SYNC,
            requestId = 0,
            sourceNode = endpoint.nodeId,
            sourceKind = RpcNodeKind.SERVICE,
            target = RpcTarget.AllClients,
            onlineClients = onlineClients(),
        )
        clients.values.forEach { deliverToChannel(sync, it.channel) }
    }

    private inner class ServerHandler : SimpleChannelInboundHandler<ByteArray>() {
        private var nodeId: String? = null

        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteArray) {
            val frame = runCatching { RpcFrameCodec.decode(msg) }.getOrElse { return }
            if (frame.type == RpcFrameType.HELLO) {
                val info = RpcClientInfo(frame.sourceNode, frame.sourceTags)
                nodeId = info.nodeId
                clients.put(info.nodeId, ClientConnection(ctx.channel(), info))
                    ?.channel
                    ?.takeIf { it != ctx.channel() }
                    ?.close()
                broadcastClientSync()
                return
            }
            route(frame, ctx.channel())
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            val disconnectedNodeId = nodeId ?: return
            val current = clients[disconnectedNodeId]
            val removed = current != null && current.channel == ctx.channel() && clients.remove(disconnectedNodeId, current)
            if (removed) broadcastClientSync()
        }
    }

    private data class ClientConnection(
        val channel: Channel,
        val info: RpcClientInfo,
    )
}
