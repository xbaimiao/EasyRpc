package com.xbaimiao.easyrpc.core

/** 当前节点的类型。service 是中心路由节点，client 是接入 service 的普通节点。 */
enum class RpcNodeKind {
    SERVICE,
    CLIENT,
}

/**
 * 在线 client 元数据。
 *
 * - [nodeId]：client 的唯一 ID，路由用的就是它。
 * - [displayName]：给人看的名字，没声明时等于 [nodeId]。
 * - [tags]：client 声明的标签，可以用 `RpcTarget.tag()` 批量调用。
 * - [metadata]：client 自定义的键值元数据，键值含义由业务自己约定。
 *
 * 用 [of] 构造可以顺带做 trim、去空和不可变拷贝，避免外部集合被后续修改影响。
 */
data class RpcClientInfo(
    val nodeId: String,
    val tags: Set<String> = emptySet(),
    val displayName: String = nodeId,
    val metadata: Map<String, String> = emptyMap(),
) {
    /** 读取单个 metadata 值。 */
    fun metadata(key: String): String? = metadata[key]

    /** 是否拥有指定 tag。 */
    fun hasTag(tag: String): Boolean = tag in tags

    companion object {
        /** 规范化构造：tags 去空白去空串，displayName 为空时回退成 nodeId，集合做不可变拷贝。 */
        fun of(
            nodeId: String,
            tags: Collection<String> = emptySet(),
            displayName: String? = null,
            metadata: Map<String, String> = emptyMap(),
        ): RpcClientInfo = RpcClientInfo(
            nodeId = nodeId,
            tags = tags.normalizedTags(),
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: nodeId,
            metadata = metadata.toMap(),
        )
    }
}

/** tags 规范化：去掉首尾空白和空串，并转成不可变 set。 */
fun Collection<String>.normalizedTags(): Set<String> = asSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .toSet()

/**
 * RPC 调用目标。
 *
 * `NettyRpcServer` 收到 request 后会按这个目标决定投递位置：
 *
 * - [Service]：交给 service 自己的 endpoint 处理。
 * - [Node]：投递给指定 nodeId，可以是某个 client，也可以是 service 自己。
 * - [All]：service 和所有 client 都会收到，调用方应该使用 `callAll`。
 * - [AllClients]：所有 client 都会收到，调用方应该使用 `callAll`。
 * - [Tag]：投递给拥有指定 tag 的所有 client，调用方通常使用 `callAll`。
 */
sealed class RpcTarget {
    /** 调用中心 service 节点。 */
    data object Service : RpcTarget()

    /** 调用指定节点。 */
    data class Node(val nodeId: String) : RpcTarget()

    /** 调用 service 和所有 client。 */
    data object All : RpcTarget()

    /** 只调用所有 client。 */
    data object AllClients : RpcTarget()

    /** 调用拥有指定 tag 的所有 client。 */
    data class Tag(val tag: String) : RpcTarget()

    companion object {
        fun service(): RpcTarget = Service
        fun node(nodeId: String): RpcTarget = Node(nodeId)
        fun all(): RpcTarget = All
        fun allClients(): RpcTarget = AllClients
        fun tag(tag: String): RpcTarget = Tag(tag)
    }
}
