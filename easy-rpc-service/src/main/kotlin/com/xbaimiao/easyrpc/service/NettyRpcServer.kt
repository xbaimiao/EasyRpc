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
import io.netty.util.concurrent.ScheduledFuture
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Netty 版 RPC service。
 *
 * 它同时扮演两个角色：
 *
 * - service 节点：可以自己注册 RPC handler。
 * - router：负责把 client 发来的 frame 转发到 service、指定 client、all 或 allClients。
 * - registry：保存在线 client 的 nodeId/displayName/tags/metadata，并同步给所有 client。
 *
 * 连接必须先用 HELLO 完成握手才会被路由。配了 [authToken] 时 HELLO 还要带上匹配的 token，
 * 否则 service 回一个 `unauthorized` ERROR 并断开连接。
 */
class NettyRpcServer(
    private val host: String = "0.0.0.0",
    private val port: Int = 29090,
    nodeId: String = "service",
    displayName: String? = null,
    metadata: Map<String, String> = emptyMap(),
    /**
     * 共享鉴权 token。所有 client 必须在 HELLO 里带上同一个值。
     *
     * 留空表示不启用鉴权，任何 client 都能连上，只适合本机开发。
     */
    authToken: String = "",
    /** client 迟迟不发 HELLO 时主动断开的等待时间。 */
    private val handshakeTimeout: Duration = Duration.ofSeconds(10),
) : RpcCaller, RpcRuntime, AutoCloseable {
    /** service 自己的 endpoint。发给 RpcTarget.service() 的请求会进入这里。 */
    override val endpoint = RpcEndpoint(
        nodeId = nodeId,
        nodeKind = RpcNodeKind.SERVICE,
        nodeDisplayName = displayName,
        nodeMetadata = metadata,
    )

    /** service 节点自己的元数据快照。 */
    val selfInfo: RpcClientInfo get() = endpoint.selfInfo

    private val authToken: String = authToken.trim()
    private val handshakeTimeoutMillis: Long = handshakeTimeout.toMillis().coerceAtLeast(1)

    /** 是否启用了鉴权。token 为空时返回 false。 */
    val authEnabled: Boolean get() = authToken.isNotEmpty()

    /**
     * 鉴权失败回调，参数是 client 声明的 nodeId 和失败原因。
     *
     * nodeId 由未授权方自己声明，不可信，打日志时注意这点。
     */
    @Volatile
    var onAuthFailure: ((String, String) -> Unit)? = null

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
        .map { it.info }
        .sortedBy { it.nodeId }

    /** 获取指定 nodeId 的在线 client。 */
    fun onlineClient(nodeId: String): RpcClientInfo? = clients[nodeId]?.info

    /** 获取拥有指定 tag 的所有在线 client。 */
    fun onlineClientsByTag(tag: String): List<RpcClientInfo> = onlineClients()
        .filter { tag in it.tags }

    /** 获取指定 nodeId 的显示名称，节点不在线时返回 null。 */
    fun displayNameOf(nodeId: String): String? = clients[nodeId]?.info?.displayName

    /** 获取指定 nodeId 的 tags，节点不在线时返回空集合。 */
    fun tagsOf(nodeId: String): Set<String> = clients[nodeId]?.info?.tags ?: emptySet()

    /** 获取指定 nodeId 的单个 metadata 值，节点不在线或没这个键时返回 null。 */
    fun metadataOf(nodeId: String, key: String): String? = clients[nodeId]?.info?.metadata?.get(key)

    /** 当前在线 client 的 nodeId 列表。 */
    fun onlineNodeIds(): List<String> = clients.keys.sorted()

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
            sourceDisplayName = endpoint.displayName,
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

    /** 校验 client HELLO 带来的 token。没配置 token 时一律放行。 */
    private fun verifyToken(presented: String): Boolean {
        if (authToken.isEmpty()) return true
        return RpcAuth.matches(authToken, presented)
    }

    private fun broadcastClientSync() {
        val sync = RpcFrame(
            type = RpcFrameType.CLIENTS_SYNC,
            requestId = 0,
            sourceNode = endpoint.nodeId,
            sourceKind = RpcNodeKind.SERVICE,
            sourceDisplayName = endpoint.displayName,
            target = RpcTarget.AllClients,
            onlineClients = onlineClients(),
        )
        clients.values.forEach { deliverToChannel(sync, it.channel) }
    }

    private inner class ServerHandler : SimpleChannelInboundHandler<ByteArray>() {
        private var nodeId: String? = null

        /** 是否已经通过 HELLO 完成握手。没握手的连接一个 frame 都不许路由。 */
        private var authenticated = false

        /** 握手超时任务。连上但迟迟不发 HELLO 的连接会被主动断掉，避免占着 socket。 */
        private var handshakeTimeoutTask: ScheduledFuture<*>? = null

        override fun channelActive(ctx: ChannelHandlerContext) {
            super.channelActive(ctx)
            // authenticated 只在这个 channel 的 event loop 上读写，不需要额外同步。
            handshakeTimeoutTask = ctx.channel().eventLoop().schedule({
                if (!authenticated) {
                    runCatching { ctx.close() }
                }
            }, handshakeTimeoutMillis, TimeUnit.MILLISECONDS)
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteArray) {
            val frame = runCatching { RpcFrameCodec.decode(msg) }.getOrElse { return }

            if (frame.type == RpcFrameType.HELLO) {
                handleHello(ctx, frame)
                return
            }

            // 握手之前不接受任何其它 frame，否则未授权连接可以直接调用 RPC。
            if (!authenticated) {
                rejectUnauthenticated(ctx, frame, "RPC handshake required before any request")
                return
            }

            // sourceNode 必须和这条连接握手时声明的 nodeId 一致。
            // 否则任何持有 token 的节点都能把 sourceNode 填成别人，让 handler 读到的
            // RpcSource.nodeId 变成伪造值，响应也会被路由到被冒充的节点去。
            val authenticatedNodeId = nodeId
            if (authenticatedNodeId != null && frame.sourceNode != authenticatedNodeId) {
                // 注意不能走 sendRouteError：它按 frame.sourceNode 找回程连接，而这里
                // sourceNode 正是伪造的，错误会被发给被冒充的节点，反而把受害者打下线。
                rejectSpoofedSource(ctx, frame, authenticatedNodeId)
                return
            }

            route(frame, ctx.channel())
        }

        private fun handleHello(ctx: ChannelHandlerContext, frame: RpcFrame) {
            if (!verifyToken(frame.authToken)) {
                rejectUnauthenticated(ctx, frame, "RPC auth token mismatch")
                return
            }

            val info = frame.toClientInfo()
            if (info.nodeId.isEmpty()) {
                rejectUnauthenticated(ctx, frame, "RPC nodeId must not be empty")
                return
            }

            // 已经握手过的连接重发 HELLO 只允许更新自己的元数据，不允许换 nodeId。
            // 合法的 updateSelfInfo 一定用同一个 nodeId；允许换的话，一条已认证连接
            // 就能顶掉任意在线节点的注册。
            val currentNodeId = nodeId
            if (currentNodeId != null && info.nodeId != currentNodeId) {
                rejectSpoofedSource(ctx, frame, currentNodeId)
                return
            }

            authenticated = true
            handshakeTimeoutTask?.cancel(false)
            handshakeTimeoutTask = null
            nodeId = info.nodeId
            clients.put(info.nodeId, ClientConnection(ctx.channel(), info))
                ?.channel
                ?.takeIf { it != ctx.channel() }
                ?.close()
            broadcastClientSync()
        }

        /**
         * 拒绝伪造 sourceNode 的 frame。
         *
         * 只把错误写回发起这条 frame 的连接本身，不按 sourceNode 路由。
         * classifier 用普通 internal_error 而不是 unauthorized：后者会让收到的 client
         * 停止重连，而这里的 client 端并没有配置问题，不该被停掉。
         */
        private fun rejectSpoofedSource(ctx: ChannelHandlerContext, frame: RpcFrame, expectedNodeId: String) {
            val error = RpcFrame(
                type = RpcFrameType.ERROR,
                requestId = frame.requestId,
                sourceNode = endpoint.nodeId,
                sourceKind = RpcNodeKind.SERVICE,
                sourceDisplayName = endpoint.displayName,
                target = RpcTarget.Node(expectedNodeId),
                group = frame.group,
                method = frame.method,
                errorClassifier = "source_mismatch",
                errorMessage = "RPC sourceNode mismatch: connection authenticated as $expectedNodeId",
            )
            runCatching { ctx.writeAndFlush(RpcFrameCodec.encode(error)) }
        }

        /** 回一个 unauthorized ERROR 再断开，让 client 能区分「token 不对」和「service 没起来」。 */
        private fun rejectUnauthenticated(ctx: ChannelHandlerContext, frame: RpcFrame, reason: String) {
            val error = RpcFrame(
                type = RpcFrameType.ERROR,
                requestId = frame.requestId,
                sourceNode = endpoint.nodeId,
                sourceKind = RpcNodeKind.SERVICE,
                sourceDisplayName = endpoint.displayName,
                target = RpcTarget.Node(frame.sourceNode),
                group = frame.group,
                method = frame.method,
                errorClassifier = RPC_ERROR_UNAUTHORIZED,
                errorMessage = reason,
            )
            runCatching { ctx.writeAndFlush(RpcFrameCodec.encode(error)) }
            onAuthFailure?.invoke(frame.sourceNode, reason)
            runCatching { ctx.close() }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            handshakeTimeoutTask?.cancel(false)
            handshakeTimeoutTask = null
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
