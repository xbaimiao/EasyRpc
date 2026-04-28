package com.xbaimiao.easyrpc.service

import com.xbaimiao.easyrpc.codec.RpcCodecs
import com.xbaimiao.easyrpc.dsl.RpcGroup

object DemoRpc : RpcGroup("demo") {
    val PING = rpc("ping")
        .param(RpcCodecs.STRING)
        .returns(RpcCodecs.STRING)
}

fun main() {
    val server = TcpRpcServer(serviceName = "demo", port = 29090)
    DemoRpc.PING.listen(server.endpoint) { text -> "pong: $text" }
    server.start()
    println("EasyRpc service started on 0.0.0.0:29090")
    server.awaitClose()
}
