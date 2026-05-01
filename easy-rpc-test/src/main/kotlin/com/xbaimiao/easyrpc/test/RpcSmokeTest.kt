package com.xbaimiao.easyrpc.test

import com.xbaimiao.easyrpc.client.NettyRpcClient
import com.xbaimiao.easyrpc.codec.RpcCodecs
import com.xbaimiao.easyrpc.core.RpcException
import com.xbaimiao.easyrpc.core.RpcTarget
import com.xbaimiao.easyrpc.dsl.BuiltinRpc
import com.xbaimiao.easyrpc.dsl.RpcGroup
import com.xbaimiao.easyrpc.service.NettyRpcServer
import java.time.Duration
import java.util.concurrent.TimeUnit

object SmokeRpc : RpcGroup("smoke") {
    val ADD = rpc("add")
        .param(RpcCodecs.INT)
        .param(RpcCodecs.INT)
        .returns(RpcCodecs.INT)

}

fun main() {
    val port = 29190
    val server = NettyRpcServer(host = "127.0.0.1", port = port, nodeId = "service")

    BuiltinRpc.PING.listen(server) { text ->
        "service:pong:$text"
    }
    SmokeRpc.ADD.listen(server) { left, right ->
        left + right
    }

    server.start()
    println("service started on 127.0.0.1:$port")

    val clientA = NettyRpcClient("127.0.0.1", port, nodeId = "client-a", tags = setOf("lobby")).connect()
    val clientB = NettyRpcClient("127.0.0.1", port, nodeId = "client-b", tags = setOf("lobby", "game")).connect()

    BuiltinRpc.PING.listen(clientA) {
        "${clientA.nodeId}:$it"
    }
    BuiltinRpc.PING.listen(clientB) {
        "${clientB.nodeId}:$it"
    }

    try {
        val ping = BuiltinRpc.PING.args("hello").call(clientA, RpcTarget.service()).get(3, TimeUnit.SECONDS)
        println("SERVICE => $ping")

        val direct = BuiltinRpc.PING.args("direct").call(clientA, RpcTarget.node("client-b")).get(3, TimeUnit.SECONDS)
        println("CLIENT_B => $direct")

        val sum = SmokeRpc.ADD.args(20, 22).call(clientA, RpcTarget.service()).get(3, TimeUnit.SECONDS)
        println("ADD => $sum")

        for (info in clientA.onlineClients()) {
            println(info.nodeId + "   " + info.tags)
        }
    } finally {
    }
    server.awaitClose()
}
