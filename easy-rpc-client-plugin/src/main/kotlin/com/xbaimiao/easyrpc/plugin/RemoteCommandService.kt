package com.xbaimiao.easyrpc.plugin

import com.xbaimiao.easyrpc.client.NettyRpcClient
import com.xbaimiao.easyrpc.core.RpcClientInfo
import com.xbaimiao.easyrpc.core.RpcTagMatch
import com.xbaimiao.easyrpc.core.RpcTarget
import com.xbaimiao.easyrpc.core.filterByTags
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** 单个节点的执行结果。 */
data class RemoteCommandResult(
    val nodeId: String,
    val displayName: String,
    val success: Boolean,
    val output: String,
)

/**
 * 批量远程命令。
 *
 * 目标解析在发起方本地完成：先从 service 同步过来的在线列表里筛出命中的节点，
 * 再对每个节点单独发一次 RPC。
 *
 * 之所以不用 `RpcTarget.tag()` + `callAll`：那条路只支持单 tag，而且响应里分不清
 * 哪条来自哪个节点。批量运维必须能逐节点看成功失败，所以宁可多发几次请求。
 */
class RemoteCommandService {
    /** 解析 tag 条件命中的在线节点。 */
    fun resolveByTags(tags: Collection<String>, match: RpcTagMatch): List<RpcClientInfo> {
        val client = EasyRpcClientPlugin.clientOrNull() ?: return emptyList()
        return client.onlineClients().filterByTags(tags, match)
    }

    /** 所有在线节点，可选是否包含自己。 */
    fun resolveAll(includeSelf: Boolean): List<RpcClientInfo> {
        val client = EasyRpcClientPlugin.clientOrNull() ?: return emptyList()
        return client.onlineClients()
            .filter { includeSelf || it.nodeId != client.nodeId }
            .sortedBy { it.nodeId }
    }

    /**
     * 对一批节点执行同一条命令。
     *
     * 每个节点一次独立 RPC，互不影响：某个节点超时或报错只反映在它自己的结果里。
     */
    fun run(
        targets: List<RpcClientInfo>,
        command: String,
        requester: String,
        timeout: Duration = Duration.ofSeconds(10),
    ): CompletableFuture<List<RemoteCommandResult>> {
        val client = EasyRpcClientPlugin.clientOrNull()
            ?: return CompletableFuture.completedFuture(emptyList())
        if (targets.isEmpty()) return CompletableFuture.completedFuture(emptyList())

        val futures = targets.map { target ->
            runOne(client, target, command, requester, timeout)
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply { futures.map { it.join() } }
    }

    private fun runOne(
        client: NettyRpcClient,
        target: RpcClientInfo,
        command: String,
        requester: String,
        timeout: Duration,
    ): CompletableFuture<RemoteCommandResult> {
        // 自身节点也统一经过 RPC 回环，避免在 /easyrpc 的命令调用栈里嵌套分发目标命令。
        // 这样所有节点都由接收端异步 handler 调度到 Bukkit 主线程，输出捕获行为保持一致。
        return ConsoleRpc.RUN
            .args(command, requester)
            .call(client, RpcTarget.node(target.nodeId), timeout)
            .handle { output, error ->
                if (error != null) {
                    RemoteCommandResult(
                        nodeId = target.nodeId,
                        displayName = target.displayName,
                        success = false,
                        output = describeError(error),
                    )
                } else {
                    RemoteCommandResult(target.nodeId, target.displayName, true, output)
                }
            }
    }

    private fun describeError(error: Throwable): String {
        val cause = generateSequence(error) { it.cause }.last()
        return when {
            cause.message?.contains("not_found") == true ->
                "目标未安装 EasyRpcClient 或未开启远程命令"
            else -> "${cause::class.java.simpleName}: ${cause.message ?: "无详情"}"
        }
    }
}
