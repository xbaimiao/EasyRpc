package com.xbaimiao.easyrpc.test

import com.xbaimiao.easyrpc.client.NettyRpcClient
import com.xbaimiao.easyrpc.codec.RpcCodecs
import com.xbaimiao.easyrpc.core.RPC_ERROR_UNAUTHORIZED
import com.xbaimiao.easyrpc.core.RpcClientInfo
import com.xbaimiao.easyrpc.core.RpcException
import com.xbaimiao.easyrpc.core.RpcFrame
import com.xbaimiao.easyrpc.core.RpcFrameCodec
import com.xbaimiao.easyrpc.core.RpcFrameType
import com.xbaimiao.easyrpc.core.RpcNodeKind
import com.xbaimiao.easyrpc.core.RpcTagMatch
import com.xbaimiao.easyrpc.core.RpcTarget
import com.xbaimiao.easyrpc.core.filterByTags
import com.xbaimiao.easyrpc.dsl.BuiltinRpc
import com.xbaimiao.easyrpc.dsl.RpcGroup
import com.xbaimiao.easyrpc.service.NettyRpcServer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
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
    val token = "smoke-test-token-1234567890"
    val server = NettyRpcServer(
        host = "127.0.0.1",
        port = port,
        nodeId = "service",
        authToken = token,
    )
    server.onAuthFailure = { nodeId, reason ->
        println("AUTH_REJECTED_BY_SERVICE => claimedNodeId=$nodeId reason=$reason")
    }

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
        authToken = token,
    ).connect()
    val clientB = NettyRpcClient(
        "127.0.0.1",
        port,
        nodeId = "client-b",
        tags = setOf("lobby", "game"),
        displayName = "生存服",
        metadata = mapOf("version" to "1.21.1"),
        authToken = token,
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

        println("-- 鉴权 --")
        println("service authEnabled => ${server.authEnabled}")
        verifySourceSpoofRejected(port, token)

        // 被冒充的 client-a 不应该受到任何影响。
        val victimStillOk = runCatching {
            BuiltinRpc.PING.args("victim-alive").call(clientA, RpcTarget.service()).get(3, TimeUnit.SECONDS)
        }
        println("被冒充方是否仍正常 => ${clientA.isConnected()} / ${victimStillOk.getOrElse { "失败: ${it.message}" }}")

        verifyAuth(port, token)

        println("-- 多 tag 匹配 --")
        verifyTagMatching()
    } finally {
    }
    server.awaitClose()
}

/**
 * 验证没握手就发 REQUEST 会被拒。
 *
 * 这是鉴权真正的边界，必须绕开 SDK 用裸 socket 打，因为 SDK 一定会先发 HELLO。
 */
private fun verifyHandshakeRequired(port: Int) {
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        val request = RpcFrame(
            type = RpcFrameType.REQUEST,
            requestId = 1,
            sourceNode = "attacker",
            sourceKind = RpcNodeKind.CLIENT,
            target = RpcTarget.Service,
            group = "builtin",
            method = "ping",
        )
        val payload = RpcFrameCodec.encode(request)

        val out = DataOutputStream(socket.getOutputStream())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()

        val input = DataInputStream(socket.getInputStream())
        val response = runCatching {
            val size = input.readInt()
            val bytes = ByteArray(size)
            input.readFully(bytes)
            RpcFrameCodec.decode(bytes)
        }.getOrNull()

        println("未握手直接发 REQUEST => classifier=${response?.errorClassifier} message=${response?.errorMessage}")
        println("是否被拒 => ${response?.errorClassifier == RPC_ERROR_UNAUTHORIZED}")
    }
}

/**
 * 验证多 tag 匹配语义，用的就是需求里那个例子。
 *
 * server1 tags=[1,2,3]，server2 tags=[2,3]：
 * - ALL 查 [1,2] 只命中 server1（server2 没有 tag 1）
 * - ALL 查 [1,2,3] 只命中 server1
 * - ALL 查 [2,3] 命中两个
 * - ANY 查 [1,2] 命中两个
 */
private fun verifyTagMatching() {
    val server1 = RpcClientInfo.of("server1", tags = listOf("1", "2", "3"))
    val server2 = RpcClientInfo.of("server2", tags = listOf("2", "3"))
    val nodes = listOf(server1, server2)

    fun show(label: String, required: List<String>, match: RpcTagMatch) {
        val hit = nodes.filterByTags(required, match).map { it.nodeId }
        println("$label ${required.joinToString(",")} => ${hit.joinToString(", ").ifEmpty { "<无>" }}")
    }

    show("ALL", listOf("1", "2"), RpcTagMatch.ALL)
    show("ALL", listOf("1", "2", "3"), RpcTagMatch.ALL)
    show("ALL", listOf("2", "3"), RpcTagMatch.ALL)
    show("ALL", listOf("1", "4"), RpcTagMatch.ALL)
    show("ANY", listOf("1", "2"), RpcTagMatch.ANY)
    show("ANY", listOf("1", "4"), RpcTagMatch.ANY)
    println("空 tag 列表是否命中全部 => ${nodes.filterByTags(emptyList(), RpcTagMatch.ALL).size} (应为 0)")
}

/**
 * 验证持有正确 token 也不能伪造 sourceNode。
 *
 * 用裸 socket：先正常握手成 spoofer，再发一个 sourceNode 填成 client-a 的 REQUEST。
 * 关键是错误必须回给发起方自己，而不是被路由到被冒充的 client-a 去。
 */
private fun verifySourceSpoofRejected(port: Int, token: String) {
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        val out = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.getInputStream())

        fun send(frame: RpcFrame) {
            val bytes = RpcFrameCodec.encode(frame)
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }

        fun receive(): RpcFrame? = runCatching {
            val size = input.readInt()
            val bytes = ByteArray(size)
            input.readFully(bytes)
            RpcFrameCodec.decode(bytes)
        }.getOrNull()

        send(
            RpcFrame(
                type = RpcFrameType.HELLO,
                requestId = 0,
                sourceNode = "spoofer",
                sourceKind = RpcNodeKind.CLIENT,
                target = RpcTarget.Service,
                authToken = token,
            )
        )
        // 握手成功后 service 会广播 CLIENTS_SYNC，先把它读掉。
        val sync = receive()
        println("spoofer 握手 => ${sync?.type}")

        send(
            RpcFrame(
                type = RpcFrameType.REQUEST,
                requestId = 99,
                sourceNode = "client-a",
                sourceKind = RpcNodeKind.CLIENT,
                target = RpcTarget.Service,
                group = "builtin",
                method = "ping",
            )
        )
        var reply = receive()
        // 可能还夹着别的 CLIENTS_SYNC，跳到 ERROR 为止。
        var guard = 0
        while (reply != null && reply.type != RpcFrameType.ERROR && guard++ < 5) {
            reply = receive()
        }
        println("伪造 sourceNode => classifier=${reply?.errorClassifier} message=${reply?.errorMessage}")
        println("是否被拒 => ${reply?.errorClassifier == "source_mismatch"}")
    }
}

/** 验证错 token 会被拒、改对 token 后 retryAuth 能恢复。 */
private fun verifyAuth(port: Int, correctToken: String) {
    val rejected = CountDownLatch(1)
    val badClient = NettyRpcClient(
        "127.0.0.1",
        port,
        nodeId = "client-bad",
        authToken = "wrong-token",
    )
    badClient.onAuthFailure = { reason ->
        println("BAD_TOKEN_REJECTED => $reason")
        rejected.countDown()
    }
    badClient.connect()

    val gotRejection = rejected.await(5, TimeUnit.SECONDS)
    println("错 token 是否被拒 => $gotRejection")
    println("isAuthRejected => ${badClient.isAuthRejected()}")

    // 被拒之后不应该还能发请求。
    val callBlocked = runCatching {
        BuiltinRpc.PING.args("should-not-work").call(badClient, RpcTarget.service()).get(2, TimeUnit.SECONDS)
    }.isFailure
    println("被拒后调用是否被阻止 => $callBlocked")

    verifyHandshakeRequired(port)

    // 换成正确 token 重连，handler 和 endpoint 都保留。
    BuiltinRpc.PING.listen(badClient) { "recovered:$it" }
    badClient.retryAuth(correctToken)
    val deadline = System.currentTimeMillis() + 5000
    while (!badClient.isConnected() && System.currentTimeMillis() < deadline) {
        Thread.sleep(100)
    }
    val reconnected = badClient.isConnected()
    println("换对 token 后是否连上 => $reconnected")
    if (reconnected) {
        val ping = runCatching {
            BuiltinRpc.PING.args("after-retry").call(badClient, RpcTarget.service()).get(3, TimeUnit.SECONDS)
        }
        println("恢复后调用 => ${ping.getOrElse { "失败: ${it.message}" }}")
    }
    badClient.close()
}
