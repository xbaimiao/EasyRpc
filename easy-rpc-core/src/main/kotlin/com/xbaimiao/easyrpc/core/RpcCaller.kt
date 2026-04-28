package com.xbaimiao.easyrpc.core

import com.xbaimiao.easyrpc.dsl.RpcMethod
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 可以发起 RPC 调用的对象。
 *
 * `NettyRpcClient` 和 `NettyRpcServer` 都实现它，所以 service 可以调 client，client 也可以调 service 或其它 client。
 */
interface RpcCaller {
    /**
     * 单响应调用。
     *
     * 目标通常是 [RpcTarget.Service] 或 [RpcTarget.Node]。
     * 如果目标是广播目标，最快的一个响应会完成 future，后续响应会被忽略。
     */
    fun <A, R> call(
        method: RpcMethod<A, R>,
        args: A,
        target: RpcTarget = RpcTarget.Service,
        timeout: Duration = Duration.ofSeconds(10),
    ): CompletableFuture<R>

    /**
     * 多响应调用。
     *
     * 目标通常是 [RpcTarget.All] 或 [RpcTarget.AllClients]。
     * 在 [collectFor] 时间窗口内收集所有响应，到期后返回当前已经收到的结果列表。
     */
    fun <A, R> callAll(
        method: RpcMethod<A, R>,
        args: A,
        target: RpcTarget = RpcTarget.AllClients,
        collectFor: Duration = Duration.ofMillis(500),
    ): CompletableFuture<List<R>>
}
