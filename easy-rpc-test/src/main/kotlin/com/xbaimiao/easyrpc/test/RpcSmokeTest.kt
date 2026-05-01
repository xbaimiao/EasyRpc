package com.xbaimiao.easyrpc.test

import com.xbaimiao.easyrpc.client.NettyRpcClient
import com.xbaimiao.easyrpc.codec.RpcCodecs
import com.xbaimiao.easyrpc.core.RpcException
import com.xbaimiao.easyrpc.core.RpcTarget
import com.xbaimiao.easyrpc.dsl.RpcGroup
import com.xbaimiao.easyrpc.service.NettyRpcServer
import java.time.Duration
import java.util.concurrent.TimeUnit

object SmokeRpc : RpcGroup("smoke") {
    val PING = rpc("ping")
        .param(RpcCodecs.STRING)
        .returns(RpcCodecs.STRING)

    val ADD = rpc("add")
        .param(RpcCodecs.INT)
        .param(RpcCodecs.INT)
        .returns(RpcCodecs.INT)

    val CLIENT_ECHO = rpc("client_echo")
        .param(RpcCodecs.STRING)
        .returns(RpcCodecs.STRING)

    val FAIL = rpc("fail")
        .returns(RpcCodecs.STRING)
}

fun main() {
    val port = 29190
    val server = NettyRpcServer(host = "127.0.0.1", port = port, nodeId = "service")

    SmokeRpc.PING.listen(server) { text ->
        "service:pong:$text"
    }
    SmokeRpc.ADD.listen(server) { left, right ->
        left + right
    }
    SmokeRpc.CLIENT_ECHO.listen(server) { text ->
        "service:echo:$text"
    }
    SmokeRpc.FAIL.listen(server) {
        throw RpcException("test_error", "this is expected")
    }

    server.start()
    println("service started on 127.0.0.1:$port")

    val clientA = NettyRpcClient("127.0.0.1", port, nodeId = "client-a", tags = setOf("lobby")).connect()
    val clientB = NettyRpcClient("127.0.0.1", port, nodeId = "client-b", tags = setOf("lobby", "game")).connect()

    SmokeRpc.CLIENT_ECHO.listen(clientA) { text -> "client-a:echo:$text" }
    SmokeRpc.CLIENT_ECHO.listen(clientB) { text -> "client-b:echo:$text" }

    try {
        val ping = SmokeRpc.PING.args("hello").call(clientA, RpcTarget.service()).get(3, TimeUnit.SECONDS)
        println("SERVICE => $ping")

        val direct = SmokeRpc.CLIENT_ECHO.args("direct").call(clientA, RpcTarget.node("client-b")).get(3, TimeUnit.SECONDS)
        println("CLIENT_B => $direct")

        val allClients = SmokeRpc.CLIENT_ECHO.args("broadcast-client").callAll(
            clientA,
            RpcTarget.allClients(),
            Duration.ofMillis(500),
        ).get(3, TimeUnit.SECONDS).sorted()
        println("ALL_CLIENTS => ${allClients.joinToString()}")

        val allNodes = SmokeRpc.CLIENT_ECHO.args("broadcast-all").callAll(
            clientA,
            RpcTarget.all(),
            Duration.ofMillis(500),
        ).get(3, TimeUnit.SECONDS).sorted()
        println("ALL => ${allNodes.joinToString()}")

        val lobbyNodes = SmokeRpc.CLIENT_ECHO.args("broadcast-lobby").callAll(
            clientA,
            RpcTarget.tag("lobby"),
            Duration.ofMillis(500),
        ).get(3, TimeUnit.SECONDS).sorted()
        println("TAG_LOBBY => ${lobbyNodes.joinToString()}")

        val sum = SmokeRpc.ADD.args(20, 22).call(clientA, RpcTarget.service()).get(3, TimeUnit.SECONDS)
        println("ADD => $sum")

        val failed = SmokeRpc.FAIL.call(clientA, RpcTarget.service()).handle { _, error ->
            val rpcError = generateSequence(error) { it.cause }.firstOrNull { it is RpcException } as? RpcException
            "${rpcError?.classifier}:${rpcError?.message}"
        }.get(3, TimeUnit.SECONDS)
        println("FAIL => $failed")
    } finally {
    }
    server.awaitClose()
}
