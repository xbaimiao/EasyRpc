package com.xbaimiao.easyrpc.test

import com.xbaimiao.easyrpc.client.TcpRpcClient
import com.xbaimiao.easyrpc.codec.RpcCodecs
import com.xbaimiao.easyrpc.core.RpcException
import com.xbaimiao.easyrpc.dsl.RpcGroup
import com.xbaimiao.easyrpc.service.TcpRpcServer
import java.util.concurrent.TimeUnit

object SmokeRpc : RpcGroup("smoke") {
    val PING = rpc("ping")
        .param(RpcCodecs.STRING)
        .returns(RpcCodecs.STRING)

    val ADD = rpc("add")
        .param(RpcCodecs.INT)
        .param(RpcCodecs.INT)
        .returns(RpcCodecs.INT)

    val FAIL = rpc("fail")
        .returns(RpcCodecs.STRING)
}

fun main() {
    val port = 29190
    val server = TcpRpcServer(serviceName = "smoke-service", host = "127.0.0.1", port = port)

    SmokeRpc.PING.listen(server.endpoint) { text ->
        "pong:$text"
    }
    SmokeRpc.ADD.listen(server.endpoint) { left, right ->
        left + right
    }
    SmokeRpc.FAIL.listen(server.endpoint) {
        throw RpcException("test_error", "this is expected")
    }

    server.start()
    println("service started on 127.0.0.1:$port")
    server.awaitClose()
}
