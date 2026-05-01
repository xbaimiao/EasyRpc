package com.xbaimiao.easyrpc.core

import com.xbaimiao.easyrpc.codec.RpcCodec
import com.xbaimiao.easyrpc.dsl.RpcMethod
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 单个 RPC 节点的运行时核心。
 *
 * endpoint 不直接知道 Netty，也不关心包从哪里来。它只负责：
 *
 * - 保存当前节点注册的 handler。
 * - 生成 requestId。
 * - 管理单响应 pending 请求。
 * - 管理多响应 pendingMany 请求。
 * - 编码 request frame、解码 response frame。
 * - 执行 handler 并返回 RESPONSE/ERROR。
 */
class RpcEndpoint(
    /** 当前节点 ID。service 默认是 `service`，client 通常是服务器名或插件自定义名。 */
    val nodeId: String,
    /** 当前节点类型，用于对端 handler 判断调用来源。 */
    val nodeKind: RpcNodeKind,
    /** handler 执行线程池。不要在 Netty IO 线程里直接跑业务代码。 */
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "easy-rpc-handler-$nodeId").apply { isDaemon = true }
    },
    /** 超时和 callAll 收集窗口定时器。 */
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "easy-rpc-timeout-$nodeId").apply { isDaemon = true }
    },
    /** 当前节点 tags，会写入发出的 RPC frame，方便 handler 从 RpcSource 读取。 */
    private val nodeTags: Set<String> = emptySet(),
) : AutoCloseable {
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, PendingCall<Any?>>()
    private val pendingMany = ConcurrentHashMap<Long, PendingMany<Any?>>()
    private val handlers = ConcurrentHashMap<String, HandlerRegistration<Any?, Any?>>()

    /** 注册一个底层 handler。通常业务代码会使用 `RpcMethod.listen(runtime) { ... }`。 */
    fun <A, R> register(method: RpcMethod<A, R>, handler: (RpcSource, A) -> CompletionStage<R>) {
        val previous = handlers.put(method.key, HandlerRegistration(method, handler) as HandlerRegistration<Any?, Any?>)
        require(previous == null) { "RPC handler already registered: ${method.key}" }
    }

    /** endpoint 级别 listen，保留给需要 RpcSource 的高级用法。 */
    fun <A, R> listen(method: RpcMethod<A, R>, handler: (RpcSource, A) -> CompletionStage<R>) = register(method, handler)

    /**
     * 发起单响应 RPC。
     *
     * 返回 future 会在第一个 RESPONSE 到达时完成；ERROR 到达时异常完成；timeout 到期后异常完成。
     */
    fun <A, R> call(
        connection: RpcConnection,
        method: RpcMethod<A, R>,
        args: A,
        target: RpcTarget = RpcTarget.Service,
        timeout: Duration = Duration.ofSeconds(10),
    ): CompletableFuture<R> {
        val requestId = ids.getAndIncrement()
        val future = CompletableFuture<Any?>()
        pending[requestId] = PendingCall(future, method as RpcMethod<Any?, Any?>)
        scheduler.schedule({
            if (pending.remove(requestId) != null) {
                future.completeExceptionally(RpcTimeoutException("RPC timeout: ${method.key}"))
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS)

        sendRequest(connection, requestId, method, args, target, future)
        return future.thenApply { it as R }
    }

    /**
     * 发起多响应 RPC。
     *
     * RESPONSE 会不断追加到 results；collectFor 到期后 future 正常完成并返回当前结果列表。
     * 单个节点 ERROR 不会让整体失败，会暂存到 errors 中，后续可按需要暴露。
     */
    fun <A, R> callMany(
        connection: RpcConnection,
        method: RpcMethod<A, R>,
        args: A,
        target: RpcTarget = RpcTarget.AllClients,
        collectFor: Duration = Duration.ofMillis(500),
    ): CompletableFuture<List<R>> {
        val requestId = ids.getAndIncrement()
        val future = CompletableFuture<List<Any?>>()
        val collector = PendingMany(future, method as RpcMethod<Any?, Any?>, Collections.synchronizedList(mutableListOf()))
        pendingMany[requestId] = collector
        scheduler.schedule({
            pendingMany.remove(requestId)?.let {
                future.complete(it.results.toList())
            }
        }, collectFor.toMillis(), TimeUnit.MILLISECONDS)

        sendRequest(connection, requestId, method, args, target, future)
        return future.thenApply { list -> list.map { it as R } }
    }

    private fun <A> sendRequest(
        connection: RpcConnection,
        requestId: Long,
        method: RpcMethod<A, *>,
        args: A,
        target: RpcTarget,
        future: CompletableFuture<*>,
    ) {
        val payload = encodePayload { method.argsCodec.encode(it, args) }
        val frame = RpcFrame(
            type = RpcFrameType.REQUEST,
            requestId = requestId,
            sourceNode = nodeId,
            sourceKind = nodeKind,
            sourceTags = nodeTags,
            target = target,
            group = method.group,
            method = method.name,
            payload = payload,
        )
        runCatching { connection.send(RpcFrameCodec.encode(frame)) }.onFailure {
            pending.remove(requestId)
            pendingMany.remove(requestId)
            future.completeExceptionally(it)
        }
    }

    /** 接收一个已经由 transport 拿到的二进制 frame。 */
    fun receive(connection: RpcConnection, packet: ByteArray) {
        val frame = runCatching { RpcFrameCodec.decode(packet) }.getOrElse { return }
        when (frame.type) {
            RpcFrameType.REQUEST -> handleRequest(connection, frame)
            RpcFrameType.RESPONSE -> handleResponse(frame)
            RpcFrameType.ERROR -> handleError(frame)
            RpcFrameType.HELLO -> Unit
            RpcFrameType.CLIENTS_SYNC -> Unit
        }
    }

    private fun handleResponse(frame: RpcFrame) {
        val many = pendingMany[frame.requestId]
        if (many != null) {
            runCatching {
                decodePayload(frame.payload, many.method.resultCodec)
            }.onSuccess { many.results += it }
            return
        }

        val call = pending.remove(frame.requestId) ?: return
        runCatching {
            decodePayload(frame.payload, call.method.resultCodec)
        }.onSuccess { call.future.complete(it) }
            .onFailure { call.future.completeExceptionally(it) }
    }

    private fun handleError(frame: RpcFrame) {
        val error = RpcException(frame.errorClassifier, frame.errorMessage)
        pending.remove(frame.requestId)?.future?.completeExceptionally(error)
        pendingMany[frame.requestId]?.errors?.add(error)
    }

    /**
     * 让当前所有未完成请求失败，但保留已经注册的 listen handler。
     *
     * client 网络断线时会调用它，避免旧请求一直等到 timeout；
     * 重连成功后 endpoint 仍然可以继续复用原来的 RPC 定义和 handler。
     */
    fun failPending(error: Throwable) {
        pending.values.forEach { it.future.completeExceptionally(error) }
        pendingMany.values.forEach { it.future.completeExceptionally(error) }
        pending.clear()
        pendingMany.clear()
    }

    private fun handleRequest(connection: RpcConnection, frame: RpcFrame) {
        val registration = handlers["${frame.group}:${frame.method}"]
        if (registration == null) {
            sendError(connection, frame, "not_found", "RPC handler not found: ${frame.group}:${frame.method}")
            return
        }
        CompletableFuture.runAsync({
            runCatching {
                val method = registration.method
                val args = decodePayload(frame.payload, method.argsCodec)
                registration.handler(RpcSource(frame.sourceNode, frame.sourceKind, frame.sourceTags), args).whenComplete { result, error ->
                    if (error != null) {
                        val rpcError = error.unwrapRpcError()
                        sendError(connection, frame, rpcError.classifier, rpcError.message)
                    } else {
                        val payload = encodePayload { method.resultCodec.encode(it, result) }
                        val response = RpcFrame(
                            type = RpcFrameType.RESPONSE,
                            requestId = frame.requestId,
                            sourceNode = nodeId,
                            sourceKind = nodeKind,
                            sourceTags = nodeTags,
                            target = RpcTarget.Node(frame.sourceNode),
                            group = frame.group,
                            method = frame.method,
                            payload = payload,
                        )
                        connection.send(RpcFrameCodec.encode(response))
                    }
                }
            }.onFailure {
                val rpcError = it.unwrapRpcError()
                sendError(connection, frame, rpcError.classifier, rpcError.message)
            }
        }, executor)
    }

    private fun sendError(connection: RpcConnection, request: RpcFrame, classifier: String, message: String) {
        val response = RpcFrame(
            type = RpcFrameType.ERROR,
            requestId = request.requestId,
            sourceNode = nodeId,
            sourceKind = nodeKind,
            sourceTags = nodeTags,
            target = RpcTarget.Node(request.sourceNode),
            group = request.group,
            method = request.method,
            errorClassifier = classifier,
            errorMessage = message,
        )
        runCatching { connection.send(RpcFrameCodec.encode(response)) }
    }

    override fun close() {
        pending.values.forEach { it.future.completeExceptionally(RpcException("closed", "RPC endpoint closed")) }
        pendingMany.values.forEach { it.future.completeExceptionally(RpcException("closed", "RPC endpoint closed")) }
        pending.clear()
        pendingMany.clear()
        scheduler.shutdownNow()
        executor.shutdownNow()
    }

    private fun Throwable.unwrapRpcError(): RpcException {
        return generateSequence(this) { it.cause }.firstOrNull { it is RpcException } as? RpcException
            ?: RpcException("internal_error", message ?: toString(), this)
    }

    private fun <T> decodePayload(payload: ByteArray, codec: RpcCodec<T>): T {
        val input = Unpooled.wrappedBuffer(payload)
        return try {
            codec.decode(input)
        } finally {
            input.release()
        }
    }

    private fun encodePayload(writer: (ByteBuf) -> Unit): ByteArray {
        val output = Unpooled.buffer()
        return try {
            writer(output)
            ByteArray(output.readableBytes()).also { output.readBytes(it) }
        } finally {
            output.release()
        }
    }

    private data class PendingCall<R>(val future: CompletableFuture<R>, val method: RpcMethod<Any?, Any?>)

    private data class PendingMany<R>(
        val future: CompletableFuture<List<R>>,
        val method: RpcMethod<Any?, Any?>,
        val results: MutableList<R>,
        val errors: MutableList<RpcException> = Collections.synchronizedList(mutableListOf()),
    )

    private data class HandlerRegistration<A, R>(
        val method: RpcMethod<A, R>,
        val handler: (RpcSource, A) -> CompletionStage<R>,
    )
}
