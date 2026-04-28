package com.xbaimiao.easyrpc.test

import com.xbaimiao.easyrpc.client.NettyRpcClient
import com.xbaimiao.easyrpc.core.RpcException
import com.xbaimiao.easyrpc.core.RpcTarget
import java.util.concurrent.TimeUnit

fun main() {
    val client = NettyRpcClient("127.0.0.1", 29190, nodeId = "manual-client").connect()
    client.use { rpc ->
        val ping = SmokeRpc.PING.args("hello").call(rpc, RpcTarget.service()).get(3, TimeUnit.SECONDS)
        println("PING => $ping")

        val sum = SmokeRpc.ADD.args(220, 22).call(rpc, RpcTarget.service()).get(3, TimeUnit.SECONDS)
        println("ADD => $sum")

        val failed = SmokeRpc.FAIL.call(rpc, RpcTarget.service()).handle { _, error ->
            val rpcError = generateSequence(error) { it.cause }.firstOrNull { it is RpcException } as? RpcException
            "${rpcError?.classifier}:${rpcError?.message}"
        }.get(3, TimeUnit.SECONDS)
        println("FAIL => $failed")
    }
}
