package com.xbaimiao.easyrpc.core

/**
 * endpoint 发送二进制 frame 的最小连接抽象。
 *
 * core 不依赖 Netty；Netty client/server 只需要把 `send(packet)` 映射到 channel.writeAndFlush。
 */
interface RpcConnection : AutoCloseable {
    val remoteName: String
    fun send(packet: ByteArray)
    override fun close()
}

/** 请求来源，用于 handler 判断是谁调用了当前 RPC。 */
data class RpcSource(
    val nodeId: String,
    val nodeKind: RpcNodeKind,
    val tags: Set<String> = emptySet(),
)
