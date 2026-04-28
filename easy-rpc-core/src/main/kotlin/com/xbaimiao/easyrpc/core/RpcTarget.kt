package com.xbaimiao.easyrpc.core

/** 当前节点的类型。service 是中心路由节点，client 是接入 service 的普通节点。 */
enum class RpcNodeKind {
    SERVICE,
    CLIENT,
}

/**
 * RPC 调用目标。
 *
 * `NettyRpcServer` 收到 request 后会按这个目标决定投递位置：
 *
 * - [Service]：交给 service 自己的 endpoint 处理。
 * - [Node]：投递给指定 nodeId，可以是某个 client，也可以是 service 自己。
 * - [All]：service 和所有 client 都会收到，调用方应该使用 `callAll`。
 * - [AllClients]：所有 client 都会收到，调用方应该使用 `callAll`。
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

    companion object {
        fun service(): RpcTarget = Service
        fun node(nodeId: String): RpcTarget = Node(nodeId)
        fun all(): RpcTarget = All
        fun allClients(): RpcTarget = AllClients
    }
}
