package com.xbaimiao.easyrpc.test

import com.xbaimiao.easyrpc.client.TcpRpcClient
import com.xbaimiao.easyrpc.core.RpcException
import java.util.concurrent.TimeUnit

fun main() {
    val client = TcpRpcClient("127.0.0.1", 29190, nodeName = "smoke-client").connect()
    client.use { client ->
        val ping = SmokeRpc.PING.args("hello").call(client).get(3, TimeUnit.SECONDS)
        println("PING => $ping")

        val sum = SmokeRpc.ADD.args(220, 22).call(client).get(3, TimeUnit.SECONDS)
        println("ADD => $sum")

        val failed = SmokeRpc.FAIL.call(client).handle { _, error ->
            val rpcError = generateSequence(error) { it.cause }.firstOrNull { it is RpcException } as? RpcException
            "${rpcError?.classifier}:${rpcError?.message}"
        }.get(3, TimeUnit.SECONDS)
        println("FAIL => $failed")
    }
}