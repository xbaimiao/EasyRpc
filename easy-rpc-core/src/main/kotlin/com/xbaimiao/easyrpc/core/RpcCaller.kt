package com.xbaimiao.easyrpc.core

import com.xbaimiao.easyrpc.dsl.RpcMethod
import java.time.Duration
import java.util.concurrent.CompletableFuture

interface RpcCaller {
    fun <A, R> call(method: RpcMethod<A, R>, args: A, timeout: Duration = Duration.ofSeconds(10)): CompletableFuture<R>
}
