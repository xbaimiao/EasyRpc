package com.xbaimiao.easyrpc.plugin

import com.xbaimiao.easyrpc.client.NettyRpcClient
import com.xbaimiao.easyrpc.core.RpcClientInfo
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * EasyRpc 的 PlaceholderAPI 变量。
 *
 * 所有变量都以 `%easyrpc_` 开头，不需要玩家上下文，控制台和 hologram 里也能用。
 *
 * 本机节点：
 *
 * ```text
 * %easyrpc_node_id%              本机 nodeId
 * %easyrpc_display_name%         本机显示名称
 * %easyrpc_tags%                 本机 tags，逗号分隔
 * %easyrpc_meta_<键>%            本机自定义元数据
 * %easyrpc_connected%            是否已连上 service，true / false
 * ```
 *
 * 其它节点：在变量后面用 `:` 加上目标 nodeId。
 *
 * ```text
 * %easyrpc_display_name:lobby-1%
 * %easyrpc_tags:lobby-1%
 * %easyrpc_meta_version:lobby-1%
 * %easyrpc_online:lobby-1%       目标节点是否在线，true / false
 * ```
 *
 * 在线列表：
 *
 * ```text
 * %easyrpc_online_players%            所有 Bukkit 节点的在线玩家总数
 * %easyrpc_online_count%              在线 client 数量
 * %easyrpc_online_nodes%              在线 nodeId，逗号分隔
 * %easyrpc_online_names%              在线显示名称，逗号分隔
 * %easyrpc_online_count_tag:lobby%    带指定 tag 的在线数量
 * %easyrpc_online_nodes_tag:lobby%    带指定 tag 的在线 nodeId
 * %easyrpc_has_tag_lobby%             本机是否有指定 tag
 * %easyrpc_has_tag_lobby:lobby-1%     目标节点是否有指定 tag
 * ```
 *
 * 节点不在线或键不存在时统一返回空字符串，方便直接拼进消息里。
 */
class EasyRpcExpansion(private val plugin: EasyRpcClientPlugin) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "easyrpc"

    override fun getAuthor(): String = "xbaimiao"

    override fun getVersion(): String = plugin.description.version

    /** 必须返回 true，否则 PAPI reload 时会把这个 expansion 注销掉。 */
    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val client = EasyRpcClientPlugin.clientOrNull() ?: return null
        // 约定用 ':' 分隔目标 nodeId，避免和 nodeId 里的下划线冲突。
        val separatorIndex = params.indexOf(':')
        val key = if (separatorIndex >= 0) params.substring(0, separatorIndex) else params
        val argument = if (separatorIndex >= 0) params.substring(separatorIndex + 1).trim() else ""

        return when {
            key.equals("node_id", ignoreCase = true) -> resolveInfo(client, argument)?.nodeId ?: ""
            key.equals("display_name", ignoreCase = true) -> resolveInfo(client, argument)?.displayName ?: ""
            key.equals("tags", ignoreCase = true) -> resolveInfo(client, argument)?.tags?.joinToString(", ") ?: ""
            key.equals("connected", ignoreCase = true) -> client.isConnected().toString()
            key.equals("online", ignoreCase = true) -> onlineFlag(client, argument)
            key.equals("online_players", ignoreCase = true) -> onlinePlayers(client)
            key.equals("online_count", ignoreCase = true) -> client.onlineClients().size.toString()
            key.equals("online_nodes", ignoreCase = true) -> client.onlineNodeIds().joinToString(", ")
            key.equals("online_names", ignoreCase = true) -> client.onlineClients().joinToString(", ") { it.displayName }
            key.equals("online_count_tag", ignoreCase = true) -> countByTag(client, argument)
            key.equals("online_nodes_tag", ignoreCase = true) -> nodesByTag(client, argument)
            key.startsWith("meta_", ignoreCase = true) -> metadata(client, key.substring("meta_".length), argument)
            key.startsWith("has_tag_", ignoreCase = true) -> hasTag(client, key.substring("has_tag_".length), argument)
            else -> null
        }
    }

    /** argument 为空表示本机节点，否则查在线注册表里的目标节点。 */
    private fun resolveInfo(client: NettyRpcClient, nodeId: String): RpcClientInfo? {
        if (nodeId.isEmpty()) return client.selfInfo
        return client.onlineClient(nodeId)
    }

    private fun onlineFlag(client: NettyRpcClient, nodeId: String): String {
        if (nodeId.isEmpty()) return client.isConnected().toString()
        return (client.onlineClient(nodeId) != null).toString()
    }

    private fun metadata(client: NettyRpcClient, metaKey: String, nodeId: String): String {
        if (metaKey.isEmpty()) return ""
        return resolveInfo(client, nodeId)?.metadata(metaKey) ?: ""
    }

    private fun hasTag(client: NettyRpcClient, tag: String, nodeId: String): String {
        if (tag.isEmpty()) return "false"
        return (resolveInfo(client, nodeId)?.hasTag(tag) == true).toString()
    }

    private fun countByTag(client: NettyRpcClient, tag: String): String {
        if (tag.isEmpty()) return "0"
        return client.onlineClientsByTag(tag).size.toString()
    }

    /** 只汇总 Bukkit 插件自动维护的 metadata，普通 SDK client 不参与玩家人数统计。 */
    private fun onlinePlayers(client: NettyRpcClient): String = client.onlineClients()
        .asSequence()
        .filter { it.metadata(EasyRpcClientPlugin.CLIENT_TYPE_METADATA_KEY) == EasyRpcClientPlugin.BUKKIT_CLIENT_TYPE }
        .mapNotNull { info ->
            info.metadata(EasyRpcClientPlugin.ONLINE_PLAYERS_METADATA_KEY)
                ?.toLongOrNull()
                ?.takeIf { it >= 0 }
        }
        .sum()
        .toString()

    private fun nodesByTag(client: NettyRpcClient, tag: String): String {
        if (tag.isEmpty()) return ""
        return client.onlineClientsByTag(tag).joinToString(", ") { it.nodeId }
    }
}
