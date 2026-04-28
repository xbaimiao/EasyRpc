package com.xbaimiao.easyrpc.core

/**
 * 一个可以承载 RPC handler 的运行时节点。
 *
 * `RpcMethod` 只描述协议本身，不应该保存 handler 或连接状态。
 * 真正的 handler 必须注册到某个正在运行的节点上，例如 `NettyRpcServer` 或 `NettyRpcClient`。
 * 这个接口把两种节点统一起来，让 DSL 可以写成：
 *
 * ```kotlin
 * MyRpc.PING.listen(server) { "pong" }
 * MyRpc.PING.listen(client) { "pong from client" }
 * ```
 */
interface RpcRuntime {
    /** 当前节点自己的 endpoint，负责 handler 表、pending 请求和响应解码。 */
    val endpoint: RpcEndpoint
}
