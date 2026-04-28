package com.xbaimiao.easyrpc.service

import com.xbaimiao.easyrpc.codec.RpcCodecs
import com.xbaimiao.easyrpc.dsl.RpcGroup

object DemoRpc : RpcGroup("demo") {
    val PING = rpc("ping")
        .param(RpcCodecs.STRING)
        .returns(RpcCodecs.STRING)
}

fun main() {
    val server = NettyRpcServer(host = "0.0.0.0", port = 29090, nodeId = "service")
    server.listen(DemoRpc.PING) { text -> "pong: $text" }
    server.start()
    println("EasyRpc Netty service started on 0.0.0.0:29090")
    server.awaitClose()
}
