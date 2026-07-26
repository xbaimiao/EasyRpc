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

/**
 * 请求来源，用于 handler 判断是谁调用了当前 RPC。
 *
 * [displayName] 和 [metadata] 是调用方在发送 frame 时带上的自我描述，
 * 不经过 service 校验，只作为展示和业务判断用的元数据。
 */
data class RpcSource(
    val nodeId: String,
    val nodeKind: RpcNodeKind,
    val tags: Set<String> = emptySet(),
    val displayName: String = nodeId,
    val metadata: Map<String, String> = emptyMap(),
) {
    /** 读取单个 metadata 值。 */
    fun metadata(key: String): String? = metadata[key]

    /** 是否拥有指定 tag。 */
    fun hasTag(tag: String): Boolean = tag in tags
}
