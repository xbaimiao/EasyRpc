package com.xbaimiao.easyrpc.dsl

import com.xbaimiao.easyrpc.codec.RpcCodec
import com.xbaimiao.easyrpc.codec.RpcCodecs
import com.xbaimiao.easyrpc.core.RpcCaller
import com.xbaimiao.easyrpc.core.RpcEndpoint
import com.xbaimiao.easyrpc.core.RpcRuntime
import com.xbaimiao.easyrpc.core.RpcSource
import com.xbaimiao.easyrpc.core.RpcTarget
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * 一组 RPC 方法的命名空间。
 *
 * 推荐每个业务协议定义一个 object 继承它，例如：
 *
 * ```kotlin
 * object MatchRpc : RpcGroup("match") {
 *     val CREATE = rpc("create").param(RpcCodecs.STRING).returns(RpcCodecs.INT)
 * }
 * ```
 *
 * group 会参与最终方法 key：`group:name`，用于服务端和客户端查找 handler。
 */
open class RpcGroup(val group: String) {
    /** 创建一个没有参数的 RPC builder，后续通过 `.param(...)` 或 `.returns(...)` 继续声明。 */
    fun rpc(name: String): RpcBuilder0 = RpcBuilder0(group, name)
}

/**
 * RPC 方法的底层描述。
 *
 * 它只保存协议结构：group、name、参数 codec 和返回值 codec。
 * 它不保存连接、handler 或 pending future；这些运行时状态都在 [RpcEndpoint] 里。
 */
class RpcMethod<A, R>(
    val group: String,
    val name: String,
    val argsCodec: RpcCodec<A>,
    val resultCodec: RpcCodec<R>,
) {
    /** handler 注册表使用的稳定 key。 */
    val key: String = "$group:$name"
}

/**
 * 已经填好参数、等待发送的 RPC 调用。
 */
class PreparedRpcCall<A, R>(private val method: RpcMethod<A, R>, private val args: A) {
    /**
     * 单响应调用。
     *
     * 适合 [RpcTarget.service] 和 [RpcTarget.node]。
     * 如果目标是 all/allClients，只有最快的一个响应会完成 future，应该改用 [callAll]。
     */
    fun call(
        caller: RpcCaller,
        target: RpcTarget = RpcTarget.Service,
        timeout: Duration = Duration.ofSeconds(10),
    ): CompletableFuture<R> = caller.call(method, args, target, timeout)

    /**
     * 多响应调用。
     *
     * 适合 [RpcTarget.all] 和 [RpcTarget.allClients]。
     * 在 collectFor 时间窗口内收集所有响应，到期后返回列表。
     */
    fun callAll(
        caller: RpcCaller,
        target: RpcTarget = RpcTarget.AllClients,
        collectFor: Duration = Duration.ofMillis(500),
    ): CompletableFuture<List<R>> = caller.callAll(method, args, target, collectFor)
}

/** 无参数 RPC。 */
class RpcMethod0<R>(internal val method: RpcMethod<Unit, R>) {
    fun call(caller: RpcCaller, target: RpcTarget = RpcTarget.Service, timeout: Duration = Duration.ofSeconds(10)): CompletableFuture<R> =
        caller.call(method, Unit, target, timeout)

    fun callAll(caller: RpcCaller, target: RpcTarget = RpcTarget.AllClients, collectFor: Duration = Duration.ofMillis(500)): CompletableFuture<List<R>> =
        caller.callAll(method, Unit, target, collectFor)

    fun listen(runtime: RpcRuntime, handler: () -> R) = listen(runtime.endpoint, handler)
    fun listen(endpoint: RpcEndpoint, handler: () -> R) = endpoint.register(method) { _, _ -> CompletableFuture.completedFuture(handler()) }
    fun listenAsync(runtime: RpcRuntime, handler: (RpcSource) -> CompletionStage<R>) = listenAsync(runtime.endpoint, handler)
    fun listenAsync(endpoint: RpcEndpoint, handler: (RpcSource) -> CompletionStage<R>) = endpoint.register(method) { source, _ -> handler(source) }
}

/** 1 参数 RPC。 */
class RpcMethod1<A, R>(internal val method: RpcMethod<Args1<A>, R>) {
    fun args(a: A): PreparedRpcCall<Args1<A>, R> = PreparedRpcCall(method, Args1(a))
    fun listen(runtime: RpcRuntime, handler: (A) -> R) = listen(runtime.endpoint, handler)
    fun listen(endpoint: RpcEndpoint, handler: (A) -> R) = endpoint.register(method) { _, args -> CompletableFuture.completedFuture(handler(args.a)) }
    fun listenAsync(runtime: RpcRuntime, handler: (RpcSource, A) -> CompletionStage<R>) = listenAsync(runtime.endpoint, handler)
    fun listenAsync(endpoint: RpcEndpoint, handler: (RpcSource, A) -> CompletionStage<R>) = endpoint.register(method) { source, args -> handler(source, args.a) }
}

/** 2 参数 RPC。 */
class RpcMethod2<A, B, R>(internal val method: RpcMethod<Args2<A, B>, R>) {
    fun args(a: A, b: B): PreparedRpcCall<Args2<A, B>, R> = PreparedRpcCall(method, Args2(a, b))
    fun listen(runtime: RpcRuntime, handler: (A, B) -> R) = listen(runtime.endpoint, handler)
    fun listen(endpoint: RpcEndpoint, handler: (A, B) -> R) = endpoint.register(method) { _, args -> CompletableFuture.completedFuture(handler(args.a, args.b)) }
    fun listenAsync(runtime: RpcRuntime, handler: (RpcSource, A, B) -> CompletionStage<R>) = listenAsync(runtime.endpoint, handler)
    fun listenAsync(endpoint: RpcEndpoint, handler: (RpcSource, A, B) -> CompletionStage<R>) = endpoint.register(method) { source, args -> handler(source, args.a, args.b) }
}

/** 3 参数 RPC。 */
class RpcMethod3<A, B, C, R>(internal val method: RpcMethod<Args3<A, B, C>, R>) {
    fun args(a: A, b: B, c: C): PreparedRpcCall<Args3<A, B, C>, R> = PreparedRpcCall(method, Args3(a, b, c))
    fun listen(runtime: RpcRuntime, handler: (A, B, C) -> R) = listen(runtime.endpoint, handler)
    fun listen(endpoint: RpcEndpoint, handler: (A, B, C) -> R) = endpoint.register(method) { _, args -> CompletableFuture.completedFuture(handler(args.a, args.b, args.c)) }
    fun listenAsync(runtime: RpcRuntime, handler: (RpcSource, A, B, C) -> CompletionStage<R>) = listenAsync(runtime.endpoint, handler)
    fun listenAsync(endpoint: RpcEndpoint, handler: (RpcSource, A, B, C) -> CompletionStage<R>) = endpoint.register(method) { source, args -> handler(source, args.a, args.b, args.c) }
}

/** 4 参数 RPC。 */
class RpcMethod4<A, B, C, D, R>(internal val method: RpcMethod<Args4<A, B, C, D>, R>) {
    fun args(a: A, b: B, c: C, d: D): PreparedRpcCall<Args4<A, B, C, D>, R> = PreparedRpcCall(method, Args4(a, b, c, d))
    fun listen(runtime: RpcRuntime, handler: (A, B, C, D) -> R) = listen(runtime.endpoint, handler)
    fun listen(endpoint: RpcEndpoint, handler: (A, B, C, D) -> R) = endpoint.register(method) { _, args -> CompletableFuture.completedFuture(handler(args.a, args.b, args.c, args.d)) }
    fun listenAsync(runtime: RpcRuntime, handler: (RpcSource, A, B, C, D) -> CompletionStage<R>) = listenAsync(runtime.endpoint, handler)
    fun listenAsync(endpoint: RpcEndpoint, handler: (RpcSource, A, B, C, D) -> CompletionStage<R>) = endpoint.register(method) { source, args -> handler(source, args.a, args.b, args.c, args.d) }
}

/** 5 参数 RPC。参数更多时建议改成一个 protobuf request 对象。 */
class RpcMethod5<A, B, C, D, E, R>(internal val method: RpcMethod<Args5<A, B, C, D, E>, R>) {
    fun args(a: A, b: B, c: C, d: D, e: E): PreparedRpcCall<Args5<A, B, C, D, E>, R> = PreparedRpcCall(method, Args5(a, b, c, d, e))
    fun listen(runtime: RpcRuntime, handler: (A, B, C, D, E) -> R) = listen(runtime.endpoint, handler)
    fun listen(endpoint: RpcEndpoint, handler: (A, B, C, D, E) -> R) = endpoint.register(method) { _, args -> CompletableFuture.completedFuture(handler(args.a, args.b, args.c, args.d, args.e)) }
    fun listenAsync(runtime: RpcRuntime, handler: (RpcSource, A, B, C, D, E) -> CompletionStage<R>) = listenAsync(runtime.endpoint, handler)
    fun listenAsync(endpoint: RpcEndpoint, handler: (RpcSource, A, B, C, D, E) -> CompletionStage<R>) = endpoint.register(method) { source, args -> handler(source, args.a, args.b, args.c, args.d, args.e) }
}

/** 无参数 RPC builder。 */
class RpcBuilder0 internal constructor(private val group: String, private val name: String) {
    fun <A> param(codec: RpcCodec<A>): RpcBuilder1<A> = RpcBuilder1(group, name, codec)
    fun <R> returns(codec: RpcCodec<R>): RpcMethod0<R> = RpcMethod0(RpcMethod(group, name, RpcCodecs.UNIT, codec))
    fun noReturn(): RpcMethod0<Unit> = returns(RpcCodecs.UNIT)
}

/** 1 参数 RPC builder。 */
class RpcBuilder1<A> internal constructor(private val group: String, private val name: String, private val a: RpcCodec<A>) {
    fun <B> param(codec: RpcCodec<B>): RpcBuilder2<A, B> = RpcBuilder2(group, name, a, codec)
    fun <R> returns(codec: RpcCodec<R>): RpcMethod1<A, R> = RpcMethod1(RpcMethod(group, name, Args1.codec(a), codec))
    fun <R> returnsNullable(codec: RpcCodec<R>): RpcMethod1<A, R?> = returns(RpcCodecs.nullable(codec))
    fun noReturn(): RpcMethod1<A, Unit> = returns(RpcCodecs.UNIT)
}

/** 2 参数 RPC builder。 */
class RpcBuilder2<A, B> internal constructor(private val group: String, private val name: String, private val a: RpcCodec<A>, private val b: RpcCodec<B>) {
    fun <C> param(codec: RpcCodec<C>): RpcBuilder3<A, B, C> = RpcBuilder3(group, name, a, b, codec)
    fun <R> returns(codec: RpcCodec<R>): RpcMethod2<A, B, R> = RpcMethod2(RpcMethod(group, name, Args2.codec(a, b), codec))
    fun <R> returnsNullable(codec: RpcCodec<R>): RpcMethod2<A, B, R?> = returns(RpcCodecs.nullable(codec))
    fun noReturn(): RpcMethod2<A, B, Unit> = returns(RpcCodecs.UNIT)
}

/** 3 参数 RPC builder。 */
class RpcBuilder3<A, B, C> internal constructor(private val group: String, private val name: String, private val a: RpcCodec<A>, private val b: RpcCodec<B>, private val c: RpcCodec<C>) {
    fun <D> param(codec: RpcCodec<D>): RpcBuilder4<A, B, C, D> = RpcBuilder4(group, name, a, b, c, codec)
    fun <R> returns(codec: RpcCodec<R>): RpcMethod3<A, B, C, R> = RpcMethod3(RpcMethod(group, name, Args3.codec(a, b, c), codec))
    fun <R> returnsNullable(codec: RpcCodec<R>): RpcMethod3<A, B, C, R?> = returns(RpcCodecs.nullable(codec))
    fun noReturn(): RpcMethod3<A, B, C, Unit> = returns(RpcCodecs.UNIT)
}

/** 4 参数 RPC builder。 */
class RpcBuilder4<A, B, C, D> internal constructor(private val group: String, private val name: String, private val a: RpcCodec<A>, private val b: RpcCodec<B>, private val c: RpcCodec<C>, private val d: RpcCodec<D>) {
    fun <E> param(codec: RpcCodec<E>): RpcBuilder5<A, B, C, D, E> = RpcBuilder5(group, name, a, b, c, d, codec)
    fun <R> returns(codec: RpcCodec<R>): RpcMethod4<A, B, C, D, R> = RpcMethod4(RpcMethod(group, name, Args4.codec(a, b, c, d), codec))
    fun <R> returnsNullable(codec: RpcCodec<R>): RpcMethod4<A, B, C, D, R?> = returns(RpcCodecs.nullable(codec))
    fun noReturn(): RpcMethod4<A, B, C, D, Unit> = returns(RpcCodecs.UNIT)
}

/** 5 参数 RPC builder。 */
class RpcBuilder5<A, B, C, D, E> internal constructor(private val group: String, private val name: String, private val a: RpcCodec<A>, private val b: RpcCodec<B>, private val c: RpcCodec<C>, private val d: RpcCodec<D>, private val e: RpcCodec<E>) {
    fun <R> returns(codec: RpcCodec<R>): RpcMethod5<A, B, C, D, E, R> = RpcMethod5(RpcMethod(group, name, Args5.codec(a, b, c, d, e), codec))
    fun <R> returnsNullable(codec: RpcCodec<R>): RpcMethod5<A, B, C, D, E, R?> = returns(RpcCodecs.nullable(codec))
    fun noReturn(): RpcMethod5<A, B, C, D, E, Unit> = returns(RpcCodecs.UNIT)
}
