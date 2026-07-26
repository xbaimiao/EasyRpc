package com.xbaimiao.easyrpc.test

import com.xbaimiao.easyrpc.client.NettyRpcClient
import com.xbaimiao.easyrpc.codec.RpcCodecs
import com.xbaimiao.easyrpc.core.RpcException
import com.xbaimiao.easyrpc.core.RpcTarget
import com.xbaimiao.easyrpc.dsl.BuiltinRpc
import com.xbaimiao.easyrpc.dsl.RpcGroup
import com.xbaimiao.easyrpc.service.NettyRpcServer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object SmokeRpc : RpcGroup("smoke") {
    val ADD = rpc("add")
        .param(RpcCodecs.INT)
        .param(RpcCodecs.INT)
        .returns(RpcCodecs.INT)

    /** 让被调用方回报它看到的调用方元数据。 */
    val WHO_CALLED_ME = rpc("who-called-me")
        .returns(RpcCodecs.STRING)
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
    SmokeRpc.WHO_CALLED_ME.listenAsync(server) { source ->
        CompletableFuture.completedFuture(
            "nodeId=${source.nodeId} displayName=${source.displayName} " +
                "tags=${source.tags} metadata=${source.metadata}"
        )
    }

    server.start()
    println("service started on 127.0.0.1:$port")

    val clientA = NettyRpcClient(
        "127.0.0.1",
        port,
        nodeId = "client-a",
        tags = setOf("lobby"),
        displayName = "大厅一号",
        metadata = mapOf("version" to "1.20.4", "region" to "cn-east"),
    ).connect()
    val clientB = NettyRpcClient(
        "127.0.0.1",
        port,
        nodeId = "client-b",
        tags = setOf("lobby", "game"),
        displayName = "生存服",
        metadata = mapOf("version" to "1.21.1"),
    ).connect()

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

        val who = SmokeRpc.WHO_CALLED_ME.call(clientA, RpcTarget.service()).get(3, TimeUnit.SECONDS)
        println("WHO_CALLED_ME => $who")

        println("-- clientA 看到的在线 client --")
        for (info in clientA.onlineClients()) {
            println("${info.nodeId} | ${info.displayName} | ${info.tags} | ${info.metadata}")
        }

        println("-- service 看到的在线 client --")
        for (info in server.onlineClients()) {
            println("${info.nodeId} | ${info.displayName} | ${info.tags} | ${info.metadata}")
        }

        println("clientB displayName via clientA => " + clientA.displayNameOf("client-b"))
        println("clientB version via service => " + server.metadataOf("client-b", "version"))

        clientA.updateSelfInfo(displayName = "大厅一号(已改名)")
        clientA.updateMetadata("players", "42")
        Thread.sleep(300)
        println("更新后 service 看到的 client-a => " + server.onlineClient("client-a"))
        println("更新后 clientB 看到的 client-a => " + clientB.onlineClient("client-a"))
    } finally {
    }
    server.awaitClose()
}
