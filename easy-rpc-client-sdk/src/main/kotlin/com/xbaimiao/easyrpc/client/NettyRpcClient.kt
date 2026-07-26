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
 * client 除了 [nodeId] 还可以声明 [displayName] 和 [metadata]，它们会随 HELLO 上报给 service，
 * 再由 service 同步给所有 client；handler 里也能从 `RpcSource` 直接读到调用方的这些字段。
 *
 * service 配了鉴权时必须传入匹配的 [authToken]，否则握手会被拒绝。
 * token 被拒后 client 会停止自动重连并回调 [onAuthFailure]，改好配置后用 [retryAuth] 重新连接。
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
    displayName: String? = null,
    metadata: Map<String, String> = emptyMap(),
    /** 共享鉴权 token，必须和 service 配置的一致。留空表示 service 侧没启用鉴权。 */
    authToken: String = "",
) : RpcCaller, RpcRuntime, AutoCloseable {
    /** 当前 client 的 endpoint。handler 和 pending 请求都存在这里。 */
    override val endpoint = RpcEndpoint(
        nodeId = nodeId,
        nodeKind = RpcNodeKind.CLIENT,
        nodeTags = tags.normalizedTags(),
        nodeDisplayName = displayName,
        nodeMetadata = metadata,
    )

    /** 当前 client 自己的元数据快照，包含 nodeId、displayName、tags 和 metadata。 */
    val selfInfo: RpcClientInfo get() = endpoint.selfInfo

    /** 当前 client 的 tags。 */
    val tags: Set<String> get() = endpoint.selfInfo.tags

    /** 当前 client 的显示名称，没声明时等于 [nodeId]。 */
    val displayName: String get() = endpoint.selfInfo.displayName

    /** 当前 client 的自定义元数据。 */
    val metadata: Map<String, String> get() = endpoint.selfInfo.metadata

    /** 当前使用的 token。可以通过 [retryAuth] 换成新值，不需要重建整个 client。 */
    @Volatile
    private var authToken: String = authToken.trim()

    /**
     * 鉴权失败回调，参数是 service 返回的原因。
     *
     * 触发后 client 会停止自动重连，需要改好 token 再调用 [retryAuth]。
     * SDK 本身不打日志，宿主（比如插件）应该在这里把失败信息写进自己的 logger。
     */
    @Volatile
    var onAuthFailure: ((String) -> Unit)? = null

    private val group: EventLoopGroup = NioEventLoopGroup()
    private val running = AtomicBoolean(false)
    private val manuallyClosed = AtomicBoolean(false)
    /** token 被 service 拒绝。置位后不再自动重连，避免拿同一个错 token 死循环。 */
    private val authRejected = AtomicBoolean(false)
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
        check(!authRejected.get()) { "RPC client auth was rejected by service: $nodeId" }
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
        check(!authRejected.get()) { "RPC client auth was rejected by service: $nodeId" }
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
    fun onlineClients(): List<RpcClientInfo> = onlineClients.values.sortedBy { it.nodeId }

    /** 获取指定 nodeId 的在线 client。 */
    fun onlineClient(nodeId: String): RpcClientInfo? = onlineClients[nodeId]

    /** 获取指定 nodeId 的显示名称，节点不在线时返回 null。 */
    fun displayNameOf(nodeId: String): String? = onlineClients[nodeId]?.displayName

    /** 获取指定 nodeId 的 tags，节点不在线时返回空集合。 */
    fun tagsOf(nodeId: String): Set<String> = onlineClients[nodeId]?.tags ?: emptySet()

    /** 获取指定 nodeId 的单个 metadata 值，节点不在线或没这个键时返回 null。 */
    fun metadataOf(nodeId: String, key: String): String? = onlineClients[nodeId]?.metadata?.get(key)

    /** 当前在线 client 的 nodeId 列表。 */
    fun onlineNodeIds(): List<String> = onlineClients.keys.sorted()

    /** 获取拥有指定 tag 的所有在线 client。 */
    fun onlineClientsByTag(tag: String): List<RpcClientInfo> = onlineClients()
        .filter { tag in it.tags }

    /**
     * 运行期更新自己的 displayName / tags / metadata，只传需要改的参数。
     *
     * 更新后会立刻重发 HELLO，让 service 覆盖注册表并把新值同步给其它 client。
     * 断线期间调用不会报错，新值会在下一次重连的 HELLO 里带上。
     */
    fun updateSelfInfo(
        tags: Collection<String>? = null,
        displayName: String? = null,
        metadata: Map<String, String>? = null,
    ): RpcClientInfo {
        val updated = endpoint.updateSelfInfo(tags, displayName, metadata)
        channel?.takeIf { it.isActive }?.let { sendHello(it) }
        return updated
    }

    /** 只更新单个 metadata 键。value 传 null 表示删除这个键。 */
    fun updateMetadata(key: String, value: String?): RpcClientInfo {
        val merged = endpoint.selfInfo.metadata.toMutableMap()
        if (value == null) merged.remove(key) else merged[key] = value
        return updateSelfInfo(metadata = merged)
    }

    private fun activateChannel(connected: Channel) {
        channel = connected
        reconnectDelayMillis = reconnectInitialDelay.toMillis().coerceAtLeast(1)
        sendHello(connected)
    }

    private fun sendHello(activeChannel: Channel) {
        val info = endpoint.selfInfo
        val hello = RpcFrame(
            type = RpcFrameType.HELLO,
            requestId = 0,
            sourceNode = nodeId,
            sourceKind = RpcNodeKind.CLIENT,
            target = RpcTarget.Service,
            sourceTags = info.tags,
            sourceDisplayName = info.displayName,
            sourceMetadata = info.metadata,
            authToken = authToken,
        )
        activeChannel.writeAndFlush(RpcFrameCodec.encode(hello))
    }

    private fun applyClientsSync(frame: RpcFrame) {
        val synced = frame.onlineClients.associateBy { it.nodeId }
        onlineClients.keys.retainAll(synced.keys)
        onlineClients.putAll(synced)
    }

    /**
     * service 拒绝了 token。
     *
     * 停掉自动重连并让 pending 请求立刻失败。token 是配置问题，重试一万次也还是错的，
     * 与其无限刷日志，不如停下来等人改配置。
     */
    private fun handleAuthFailure(reason: String) {
        if (!authRejected.compareAndSet(false, true)) return
        endpoint.failPending(RpcException(RPC_ERROR_UNAUTHORIZED, reason))
        onlineClients.clear()
        onAuthFailure?.invoke(reason)
    }

    /**
     * 换一个 token 重新尝试连接。
     *
     * 用于 token 被拒之后运维改好配置、执行 reload 的场景，不需要重建整个 client，
     * 已经注册的 listen handler 都能保留。返回 false 表示 client 已关闭或当前并没有被拒。
     */
    fun retryAuth(newToken: String = authToken): Boolean {
        if (!running.get() || manuallyClosed.get()) return false
        if (!authRejected.compareAndSet(true, false)) return false
        authToken = newToken.trim()
        reconnectDelayMillis = reconnectInitialDelay.toMillis().coerceAtLeast(1)
        connectAsync()
        return true
    }

    /** token 是否已经被 service 拒绝。true 表示不会再自动重连。 */
    fun isAuthRejected(): Boolean = authRejected.get()

    private fun scheduleReconnect() {
        if (!running.get() || manuallyClosed.get()) return
        // token 被拒时不再重连。
        if (authRejected.get()) return
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
            // service 拒绝握手时会回 unauthorized ERROR，这类错误不属于某个 pending 请求，
            // 必须在这里单独处理，否则会被 endpoint 当成找不到 requestId 直接丢掉。
            if (frame.type == RpcFrameType.ERROR && frame.errorClassifier == RPC_ERROR_UNAUTHORIZED) {
                handleAuthFailure(frame.errorMessage)
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
