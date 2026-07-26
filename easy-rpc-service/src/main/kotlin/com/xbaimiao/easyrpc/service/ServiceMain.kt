package com.xbaimiao.easyrpc.service

import com.xbaimiao.easyrpc.dsl.BuiltinRpc

fun main() {
    val config = loadServiceConfig()

    val server = NettyRpcServer(
        host = config.host,
        port = config.port,
        nodeId = config.nodeId,
        displayName = config.displayName,
        authToken = config.authToken,
    )

    server.onAuthFailure = { nodeId, reason ->
        // nodeId 是未授权方自己声明的，不可信，只作为排查线索。
        println("EasyRpc auth rejected: claimedNodeId=$nodeId reason=$reason")
    }

    BuiltinRpc.PING.listen(server) { text -> "pong:$text" }

    server.start()
    println(
        "EasyRpc service started on ${config.host}:${config.port} " +
            "(nodeId=${config.nodeId}, displayName=${server.selfInfo.displayName})"
    )
    if (server.authEnabled) {
        println("EasyRpc auth enabled")
    } else {
        println("WARNING: EasyRpc auth is DISABLED, any node reaching this port can join and call every RPC")
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        println("EasyRpc service shutting down...")
        server.close()
    })

    server.awaitClose()
}
