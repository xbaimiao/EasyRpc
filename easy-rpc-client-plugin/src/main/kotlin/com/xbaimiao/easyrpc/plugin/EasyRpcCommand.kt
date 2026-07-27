package com.xbaimiao.easyrpc.plugin

import com.xbaimiao.easyrpc.core.RpcClientInfo
import com.xbaimiao.easyrpc.core.RpcTagMatch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/easyrpc` 运维命令。
 *
 * - `status`：看当前连接状态、本机元数据和在线节点。
 * - `nodes`：列出所有在线节点的 nodeId / displayName / tags。
 * - `reconnect`：重新读取 config.yml 的 auth-token 并重连。
 * - `run <nodeId> <命令>`：在指定节点执行命令。
 * - `tagrun <tag1,tag2> <命令>`：在同时拥有全部指定 tag 的节点执行。
 * - `anyrun <tag1,tag2> <命令>`：在拥有任意一个指定 tag 的节点执行。
 * - `allrun <命令>`：在所有在线节点执行。
 */
class EasyRpcCommand(private val plugin: EasyRpcClientPlugin) : CommandExecutor, TabCompleter {
    private val remote = RemoteCommandService(plugin)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "reconnect" -> sender.sendMessage(plugin.reloadAuth())
            "nodes" -> sendNodes(sender)
            "run" -> runOnNode(sender, args)
            "tagrun" -> runOnTags(sender, args, RpcTagMatch.ALL, "tagrun")
            "anyrun" -> runOnTags(sender, args, RpcTagMatch.ANY, "anyrun")
            "allrun" -> runOnAll(sender, args)
            "help" -> sendHelp(sender)
            else -> sendStatus(sender)
        }
        return true
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("EasyRpc 命令:")
        sender.sendMessage("  /easyrpc status - 连接状态")
        sender.sendMessage("  /easyrpc nodes - 在线节点列表")
        sender.sendMessage("  /easyrpc reconnect - 重读 token 并重连")
        sender.sendMessage("  /easyrpc run <nodeId> <命令> - 在指定节点执行")
        sender.sendMessage("  /easyrpc tagrun <tag1,tag2> <命令> - 在同时拥有全部 tag 的节点执行")
        sender.sendMessage("  /easyrpc anyrun <tag1,tag2> <命令> - 在拥有任意 tag 的节点执行")
        sender.sendMessage("  /easyrpc allrun <命令> - 在所有在线节点执行")
    }

    private fun runOnNode(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            sender.sendMessage("用法: /easyrpc run <nodeId> <命令>")
            return
        }
        val nodeId = args[1]
        val target = EasyRpcClientPlugin.clientOrNull()?.onlineClient(nodeId)
        if (target == null) {
            sender.sendMessage("节点不在线: $nodeId")
            return
        }
        dispatch(sender, listOf(target), args.drop(2).joinToString(" "))
    }

    private fun runOnTags(sender: CommandSender, args: Array<out String>, match: RpcTagMatch, label: String) {
        if (args.size < 3) {
            sender.sendMessage("用法: /easyrpc $label <tag1,tag2> <命令>")
            return
        }
        val tags = args[1].split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (tags.isEmpty()) {
            sender.sendMessage("请至少指定一个 tag")
            return
        }
        val targets = remote.resolveByTags(tags, match)
        if (targets.isEmpty()) {
            val how = if (match == RpcTagMatch.ALL) "同时拥有全部" else "拥有任意一个"
            sender.sendMessage("没有${how} [${tags.joinToString(", ")}] 的在线节点")
            return
        }
        dispatch(sender, targets, args.drop(2).joinToString(" "))
    }

    private fun runOnAll(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage("用法: /easyrpc allrun <命令>")
            return
        }
        val targets = remote.resolveAll(includeSelf = true)
        if (targets.isEmpty()) {
            sender.sendMessage("当前没有在线节点")
            return
        }
        dispatch(sender, targets, args.drop(1).joinToString(" "))
    }

    private fun dispatch(sender: CommandSender, targets: List<RpcClientInfo>, rawCommand: String) {
        // 下发命令比查状态危险得多，单独要一个权限。
        if (!sender.hasPermission("easyrpc.run")) {
            sender.sendMessage("你没有权限执行远程命令 (easyrpc.run)")
            return
        }
        val client = EasyRpcClientPlugin.clientOrNull()
        if (client == null) {
            sender.sendMessage("EasyRpc client 未启动")
            return
        }
        if (!client.isConnected()) {
            sender.sendMessage("EasyRpc 未连接到 service，无法下发命令")
            return
        }
        val command = rawCommand.removePrefix("/").trim()
        if (command.isEmpty()) {
            sender.sendMessage("命令不能为空")
            return
        }

        sender.sendMessage("正在对 ${targets.size} 个节点执行: $command")
        val requester = "${client.nodeId}/${sender.name}"
        remote.run(targets, command, requester).whenComplete { results, error ->
            // 回调在 Netty 线程上，Bukkit 的 sendMessage 不保证线程安全，回主线程再输出。
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (error != null) {
                    sender.sendMessage("批量执行失败: ${error.message}")
                    return@Runnable
                }
                val ok = results.count { it.success }
                sender.sendMessage("执行完成: 成功 $ok / 共 ${results.size}")
                results.forEach { result ->
                    val flag = if (result.success) "[OK]" else "[FAIL]"
                    sender.sendMessage("$flag ${result.nodeId} (${result.displayName})")
                    result.output.lineSequence()
                        .filter { it.isNotBlank() }
                        .take(10)
                        .forEach { sender.sendMessage("    $it") }
                }
            })
        }
    }

    private fun sendStatus(sender: CommandSender) {
        val client = EasyRpcClientPlugin.clientOrNull()
        if (client == null) {
            sender.sendMessage("EasyRpc: client 未启动")
            return
        }
        val state = when {
            client.isAuthRejected() -> "鉴权被拒绝（改好 auth-token 后执行 /easyrpc reconnect）"
            client.isConnected() -> "已连接"
            else -> "重连中"
        }
        val info = client.selfInfo
        sender.sendMessage("EasyRpc 状态: $state")
        sender.sendMessage("  nodeId: ${info.nodeId}")
        sender.sendMessage("  displayName: ${info.displayName}")
        sender.sendMessage("  tags: ${info.tags.joinToString(", ").ifEmpty { "<无>" }}")
        sender.sendMessage("  metadata: ${info.metadata.entries.joinToString(", ").ifEmpty { "<无>" }}")
        sender.sendMessage("  接受远程命令: ${plugin.acceptRemoteCommands()}")
        sender.sendMessage("  在线节点数: ${client.onlineClients().size}")
    }

    private fun sendNodes(sender: CommandSender) {
        val client = EasyRpcClientPlugin.clientOrNull()
        if (client == null) {
            sender.sendMessage("EasyRpc: client 未启动")
            return
        }
        val nodes = client.onlineClients()
        if (nodes.isEmpty()) {
            sender.sendMessage("EasyRpc: 当前没有在线节点")
            return
        }
        sender.sendMessage("EasyRpc 在线节点 (${nodes.size}):")
        nodes.forEach { node ->
            sender.sendMessage("  ${node.nodeId} | ${node.displayName} | ${node.tags.joinToString(", ")}")
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): List<String> {
        val client = EasyRpcClientPlugin.clientOrNull()
        return when {
            args.size == 1 -> listOf("status", "nodes", "reconnect", "run", "tagrun", "anyrun", "allrun", "help")
                .filter { it.startsWith(args[0], ignoreCase = true) }

            args.size == 2 && args[0].equals("run", ignoreCase = true) ->
                client?.onlineNodeIds()?.filter { it.startsWith(args[1], ignoreCase = true) } ?: emptyList()

            args.size == 2 && (args[0].equals("tagrun", ignoreCase = true) || args[0].equals("anyrun", ignoreCase = true)) ->
                completeTags(client, args[1])

            else -> emptyList()
        }
    }

    /** tag 补全支持逗号续写：已经输入 `lobby,` 时继续补下一个 tag。 */
    private fun completeTags(client: com.xbaimiao.easyrpc.client.NettyRpcClient?, current: String): List<String> {
        val allTags = client?.onlineClients()?.flatMap { it.tags }?.distinct()?.sorted() ?: return emptyList()
        val lastComma = current.lastIndexOf(',')
        if (lastComma < 0) {
            return allTags.filter { it.startsWith(current, ignoreCase = true) }
        }
        val prefix = current.substring(0, lastComma + 1)
        val typed = current.substring(lastComma + 1)
        val used = prefix.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return allTags
            .filter { it !in used && it.startsWith(typed, ignoreCase = true) }
            .map { prefix + it }
    }
}
