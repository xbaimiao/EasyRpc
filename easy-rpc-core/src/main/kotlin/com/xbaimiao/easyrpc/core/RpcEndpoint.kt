package com.xbaimiao.easyrpc.core

import com.xbaimiao.easyrpc.dsl.RpcMethod
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RpcEndpoint(
    val serviceName: String,
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "easy-rpc-handler").apply { isDaemon = true }
    },
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "easy-rpc-timeout").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, PendingCall<Any?>>()
    private val handlers = ConcurrentHashMap<String, HandlerRegistration<Any?, Any?>>()

    fun <A, R> register(method: RpcMethod<A, R>, handler: (RpcSource, A) -> CompletionStage<R>) {
        val previous = handlers.put(method.key, HandlerRegistration(method, handler) as HandlerRegistration<Any?, Any?>)
        require(previous == null) { "RPC handler already registered: ${method.key}" }
    }

    fun <A, R> call(
        connection: RpcConnection,
        method: RpcMethod<A, R>,
        args: A,
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

        val payload = encodePayload { method.argsCodec.encode(it, args) }
        val frame = RpcFrame(RpcFrameType.REQUEST, requestId, serviceName, method.group, method.name, payload)
        runCatching { connection.send(RpcFrameCodec.encode(frame)) }.onFailure {
            pending.remove(requestId)
            future.completeExceptionally(it)
        }
        return future.thenApply { it as R }
    }

    fun receive(connection: RpcConnection, packet: ByteArray) {
        val frame = runCatching { RpcFrameCodec.decode(packet) }.getOrElse { return }
        when (frame.type) {
            RpcFrameType.REQUEST -> handleRequest(connection, frame)
            RpcFrameType.RESPONSE -> handleResponse(frame)
            RpcFrameType.ERROR -> pending.remove(frame.requestId)?.future
                ?.completeExceptionally(RpcException(frame.errorClassifier, frame.errorMessage))
        }
    }

    private fun handleResponse(frame: RpcFrame) {
        val call = pending.remove(frame.requestId) ?: return
        runCatching {
            DataInputStream(ByteArrayInputStream(frame.payload)).use { call.method.resultCodec.decode(it) }
        }.onSuccess { call.future.complete(it) }
            .onFailure { call.future.completeExceptionally(it) }
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
                val args = DataInputStream(ByteArrayInputStream(frame.payload)).use { method.argsCodec.decode(it) }
                registration.handler(RpcSource(connection.remoteName), args).whenComplete { result, error ->
                    if (error != null) {
                        val rpcError = error.unwrapRpcError()
                        sendError(connection, frame, rpcError.classifier, rpcError.message)
                    } else {
                        val payload = encodePayload { method.resultCodec.encode(it, result) }
                        val response = RpcFrame(RpcFrameType.RESPONSE, frame.requestId, serviceName, frame.group, frame.method, payload)
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
            RpcFrameType.ERROR,
            request.requestId,
            serviceName,
            request.group,
            request.method,
            ByteArray(0),
            classifier,
            message,
        )
        runCatching { connection.send(RpcFrameCodec.encode(response)) }
    }

    override fun close() {
        pending.values.forEach { it.future.completeExceptionally(RpcException("closed", "RPC endpoint closed")) }
        pending.clear()
        scheduler.shutdownNow()
        executor.shutdownNow()
    }

    private fun Throwable.unwrapRpcError(): RpcException {
        return generateSequence(this) { it.cause }.firstOrNull { it is RpcException } as? RpcException
            ?: RpcException("internal_error", message ?: toString(), this)
    }

    private fun <T> encodePayload(writer: (DataOutputStream) -> T): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { writer(it) }
        return bytes.toByteArray()
    }

    private data class PendingCall<R>(val future: CompletableFuture<R>, val method: RpcMethod<Any?, Any?>)

    private data class HandlerRegistration<A, R>(
        val method: RpcMethod<A, R>,
        val handler: (RpcSource, A) -> CompletionStage<R>,
    )
}


