package com.xbaimiao.easyrpc.service

import com.xbaimiao.easyrpc.dsl.BuiltinRpc

fun main() {
    val config = loadServiceConfig()

    val server = NettyRpcServer(
        host = config.host,
        port = config.port,
        nodeId = config.nodeId,
        displayName = config.displayName,
    )

    BuiltinRpc.PING.listen(server) { text -> "pong:$text" }

    server.start()
    println(
        "EasyRpc service started on ${config.host}:${config.port} " +
            "(nodeId=${config.nodeId}, displayName=${server.selfInfo.displayName})"
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        println("EasyRpc service shutting down...")
        server.close()
    })

    server.awaitClose()
}
