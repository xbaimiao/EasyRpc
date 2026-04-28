package com.xbaimiao.easyrpc.dsl

import com.xbaimiao.easyrpc.codec.RpcCodec
import com.xbaimiao.easyrpc.codec.RpcCodecs
import com.xbaimiao.easyrpc.core.RpcCaller
import com.xbaimiao.easyrpc.core.RpcEndpoint
import com.xbaimiao.easyrpc.core.RpcSource
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

open class RpcGroup(val group: String) {
    fun rpc(name: String): RpcBuilder0 = RpcBuilder0(group, name)
}

class RpcMethod<A, R>(
    val group: String,
    val name: String,
    val argsCodec: RpcCodec<A>,
    val resultCodec: RpcCodec<R>,
) {
    val key: String = "$group:$name"
}

class PreparedRpcCall<A, R>(private val method: RpcMethod<A, R>, private val args: A) {
    fun call(caller: RpcCaller, timeout: Duration = Duration.ofSeconds(10)): CompletableFuture<R> = caller.call(method, args, timeout)
}

class RpcMethod0<R>(internal val method: RpcMethod<Unit, R>) {
    fun call(caller: RpcCaller, timeout: Duration = Duration.ofSeconds(10)): CompletableFuture<R> = caller.call(method, Unit, timeout)
    fun listen(endpoint: RpcEndpoint, handler: () -> R) = endpoint.register(method) { _, _ -> CompletableFuture.completedFuture(handler()) }
    fun listenAsync(endpoint: RpcEndpoint, handler: (RpcSource) -> CompletionStage<R>) = endpoint.register(method) { source, _ -> handler(source) }
}

class RpcMethod1<A, R>(internal val method: RpcMethod<Args1<A>, R>) {
    fun args(a: A): PreparedRpcCall<Args1<A>, R> = PreparedRpcCall(method, Args1(a))
    fun listen(endpoint: RpcEndpoint, handler: (A) -> R) = endpoint.register(method) { _, args -> CompletableFuture.completedFuture(handler(args.a)) }
    fun listenAsync(endpoint: RpcEndpoint, handler: (RpcSource, A) -> CompletionStage<R>) = endpoint.register(method) { source, args -> handler(source, args.a) }
}

class RpcMethod2<A, B, R>(internal val method: RpcMethod<Args2<A, B>, R>) {
    fun args(a: A, b: B): PreparedRpcCall<Args2<A, B>, R> = PreparedRpcCall(method, Args2(a, b))
    fun listen(endpoint: RpcEndpoint, handler: (A, B) -> R) = endpoint.register(method) { _, args -> CompletableFuture.completedFuture(handler(args.a, args.b)) }
    fun listenAsync(endpoint: RpcEndpoint, handler: (RpcSource, A, B) -> CompletionStage<R>) = endpoint.register(method) { source, args -> handler(source, args.a, args.b) }
}

class RpcMethod3<A, B, C, R>(internal val method: RpcMethod<Args3<A, B, C>, R>) {
    fun args(a: A, b: B, c: C): PreparedRpcCall<Args3<A, B, C>, R> = PreparedRpcCall(method, Args3(a, b, c))
    fun listen(endpoint: RpcEndpoint, handler: (A, B, C) -> R) = endpoint.register(method) { _, args -> CompletableFuture.completedFuture(handler(args.a, args.b, args.c)) }
    fun listenAsync(endpoint: RpcEndpoint, handler: (RpcSource, A, B, C) -> CompletionStage<R>) = endpoint.register(method) { source, args -> handler(source, args.a, args.b, args.c) }
}

class RpcMethod4<A, B, C, D, R>(internal val method: RpcMethod<Args4<A, B, C, D>, R>) {
    fun args(a: A, b: B, c: C, d: D): PreparedRpcCall<Args4<A, B, C, D>, R> = PreparedRpcCall(method, Args4(a, b, c, d))
    fun listen(endpoint: RpcEndpoint, handler: (A, B, C, D) -> R) = endpoint.register(method) { _, args -> CompletableFuture.completedFuture(handler(args.a, args.b, args.c, args.d)) }
    fun listenAsync(endpoint: RpcEndpoint, handler: (RpcSource, A, B, C, D) -> CompletionStage<R>) = endpoint.register(method) { source, args -> handler(source, args.a, args.b, args.c, args.d) }
}

class RpcMethod5<A, B, C, D, E, R>(internal val method: RpcMethod<Args5<A, B, C, D, E>, R>) {
    fun args(a: A, b: B, c: C, d: D, e: E): PreparedRpcCall<Args5<A, B, C, D, E>, R> = PreparedRpcCall(method, Args5(a, b, c, d, e))
    fun listen(endpoint: RpcEndpoint, handler: (A, B, C, D, E) -> R) = endpoint.register(method) { _, args -> CompletableFuture.completedFuture(handler(args.a, args.b, args.c, args.d, args.e)) }
    fun listenAsync(endpoint: RpcEndpoint, handler: (RpcSource, A, B, C, D, E) -> CompletionStage<R>) = endpoint.register(method) { source, args -> handler(source, args.a, args.b, args.c, args.d, args.e) }
}

class RpcBuilder0 internal constructor(private val group: String, private val name: String) {
    fun <A> param(codec: RpcCodec<A>): RpcBuilder1<A> = RpcBuilder1(group, name, codec)
    fun <R> returns(codec: RpcCodec<R>): RpcMethod0<R> = RpcMethod0(RpcMethod(group, name, RpcCodecs.UNIT, codec))
    fun noReturn(): RpcMethod0<Unit> = returns(RpcCodecs.UNIT)
}

class RpcBuilder1<A> internal constructor(private val group: String, private val name: String, private val a: RpcCodec<A>) {
    fun <B> param(codec: RpcCodec<B>): RpcBuilder2<A, B> = RpcBuilder2(group, name, a, codec)
    fun <R> returns(codec: RpcCodec<R>): RpcMethod1<A, R> = RpcMethod1(RpcMethod(group, name, Args1.codec(a), codec))
    fun <R> returnsNullable(codec: RpcCodec<R>): RpcMethod1<A, R?> = returns(RpcCodecs.nullable(codec))
    fun noReturn(): RpcMethod1<A, Unit> = returns(RpcCodecs.UNIT)
}

class RpcBuilder2<A, B> internal constructor(private val group: String, private val name: String, private val a: RpcCodec<A>, private val b: RpcCodec<B>) {
    fun <C> param(codec: RpcCodec<C>): RpcBuilder3<A, B, C> = RpcBuilder3(group, name, a, b, codec)
    fun <R> returns(codec: RpcCodec<R>): RpcMethod2<A, B, R> = RpcMethod2(RpcMethod(group, name, Args2.codec(a, b), codec))
    fun <R> returnsNullable(codec: RpcCodec<R>): RpcMethod2<A, B, R?> = returns(RpcCodecs.nullable(codec))
    fun noReturn(): RpcMethod2<A, B, Unit> = returns(RpcCodecs.UNIT)
}

class RpcBuilder3<A, B, C> internal constructor(private val group: String, private val name: String, private val a: RpcCodec<A>, private val b: RpcCodec<B>, private val c: RpcCodec<C>) {
    fun <D> param(codec: RpcCodec<D>): RpcBuilder4<A, B, C, D> = RpcBuilder4(group, name, a, b, c, codec)
    fun <R> returns(codec: RpcCodec<R>): RpcMethod3<A, B, C, R> = RpcMethod3(RpcMethod(group, name, Args3.codec(a, b, c), codec))
    fun <R> returnsNullable(codec: RpcCodec<R>): RpcMethod3<A, B, C, R?> = returns(RpcCodecs.nullable(codec))
    fun noReturn(): RpcMethod3<A, B, C, Unit> = returns(RpcCodecs.UNIT)
}

class RpcBuilder4<A, B, C, D> internal constructor(private val group: String, private val name: String, private val a: RpcCodec<A>, private val b: RpcCodec<B>, private val c: RpcCodec<C>, private val d: RpcCodec<D>) {
    fun <E> param(codec: RpcCodec<E>): RpcBuilder5<A, B, C, D, E> = RpcBuilder5(group, name, a, b, c, d, codec)
    fun <R> returns(codec: RpcCodec<R>): RpcMethod4<A, B, C, D, R> = RpcMethod4(RpcMethod(group, name, Args4.codec(a, b, c, d), codec))
    fun <R> returnsNullable(codec: RpcCodec<R>): RpcMethod4<A, B, C, D, R?> = returns(RpcCodecs.nullable(codec))
    fun noReturn(): RpcMethod4<A, B, C, D, Unit> = returns(RpcCodecs.UNIT)
}

class RpcBuilder5<A, B, C, D, E> internal constructor(private val group: String, private val name: String, private val a: RpcCodec<A>, private val b: RpcCodec<B>, private val c: RpcCodec<C>, private val d: RpcCodec<D>, private val e: RpcCodec<E>) {
    fun <R> returns(codec: RpcCodec<R>): RpcMethod5<A, B, C, D, E, R> = RpcMethod5(RpcMethod(group, name, Args5.codec(a, b, c, d, e), codec))
    fun <R> returnsNullable(codec: RpcCodec<R>): RpcMethod5<A, B, C, D, E, R?> = returns(RpcCodecs.nullable(codec))
    fun noReturn(): RpcMethod5<A, B, C, D, E, Unit> = returns(RpcCodecs.UNIT)
}
