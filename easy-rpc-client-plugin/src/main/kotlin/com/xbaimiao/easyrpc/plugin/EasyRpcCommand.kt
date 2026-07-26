package com.xbaimiao.easyrpc.plugin

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/easyrpc` 运维命令。
 *
 * - `status`：看当前连接状态、本机元数据和在线节点。
 * - `reconnect`：重新读取 config.yml 的 auth-token 并重连，用于 token 改错之后恢复。
 * - `nodes`：列出所有在线节点的 nodeId / displayName / tags。
 */
class EasyRpcCommand(private val plugin: EasyRpcClientPlugin) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "reconnect" -> sender.sendMessage(plugin.reloadAuth())
            "nodes" -> sendNodes(sender)
            else -> sendStatus(sender)
        }
        return true
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
        if (args.size != 1) return emptyList()
        return listOf("status", "reconnect", "nodes").filter { it.startsWith(args[0], ignoreCase = true) }
    }
}
