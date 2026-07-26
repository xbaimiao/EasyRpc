package com.xbaimiao.easyrpc.plugin

import com.xbaimiao.easyrpc.client.NettyRpcClient
import com.xbaimiao.easyrpc.core.normalizedTags
import org.bukkit.plugin.java.JavaPlugin

/**
 * Paper 插件版 RPC client 包装。
 *
 * 这个插件只负责维护一个 [NettyRpcClient] 连接，不承载 service/router。
 * 其它插件可以依赖它，然后通过 [client] 获取 SDK client 发起 RPC 或注册 handler。
 */
class EasyRpcClientPlugin : JavaPlugin() {
    companion object {
        private var plugin: EasyRpcClientPlugin? = null

        fun instance(): EasyRpcClientPlugin = plugin ?: error("EasyRpcClientPlugin is not enabled")
        fun clientOrNull(): NettyRpcClient? = plugin?.client
        fun client(): NettyRpcClient = clientOrNull() ?: error("EasyRpc client is not connected")
    }

    private var client: NettyRpcClient? = null
    private var expansion: EasyRpcExpansion? = null

    override fun onEnable() {
        plugin = this
        saveDefaultConfig()
        if (config.getBoolean("connect-on-enable", true)) {
            runCatching { connect() }.onFailure {
                logger.warning("EasyRpc client connect failed: ${it.message}")
            }
        }
        registerPlaceholders()
    }

    override fun onDisable() {
        // persist=true 的 expansion 不会被 PAPI 自动回收，这里手动注销。
        expansion?.let { runCatching { it.unregister() } }
        expansion = null
        disconnect()
        plugin = null
    }

    /** 按 config.yml 启动 Netty RPC client。重复调用会复用已有 client。 */
    fun connect(): NettyRpcClient {
        client?.let { return it }
        val host = config.getString("host", "127.0.0.1") ?: "127.0.0.1"
        val port = config.getInt("port", 29090)
        val nodeId = config.getString("node-id", server.name) ?: server.name
        val tags = config.getStringList("tags").normalizedTags()
        val displayName = config.getString("display-name")?.trim()?.takeIf { it.isNotEmpty() }
        val metadata = readMetadata()
        val rpcClient = NettyRpcClient(
            host = host,
            port = port,
            nodeId = nodeId,
            tags = tags,
            displayName = displayName,
            metadata = metadata,
        ).connect()
        client = rpcClient
        val identity = "$nodeId (${rpcClient.displayName})"
        if (rpcClient.isConnected()) {
            logger.info("EasyRpc client connected to $host:$port as $identity")
        } else {
            logger.warning("EasyRpc service is unavailable, client will reconnect to $host:$port as $identity")
        }
        return rpcClient
    }

    /** 关闭当前 RPC 连接。 */
    fun disconnect() {
        client?.close()
        client = null
    }

    /**
     * 注册 PlaceholderAPI 变量。
     *
     * 本插件是 `load: STARTUP`，enable 时机早于 PlaceholderAPI，所以必须延迟到第一个 tick 再注册。
     * 没装 PlaceholderAPI 时安静跳过，不影响 RPC 功能。
     */
    private fun registerPlaceholders() {
        server.scheduler.runTask(this, Runnable {
            if (!server.pluginManager.isPluginEnabled("PlaceholderAPI")) return@Runnable
            runCatching {
                EasyRpcExpansion(this).also {
                    it.register()
                    expansion = it
                }
            }.onSuccess {
                logger.info("EasyRpc placeholders registered: %easyrpc_node_id%, %easyrpc_meta_<key>% ...")
            }.onFailure {
                logger.warning("EasyRpc placeholder register failed: ${it.message}")
            }
        })
    }

    /** 读取 config.yml 里的 metadata 段，值统一转成 String。 */
    private fun readMetadata(): Map<String, String> {
        val section = config.getConfigurationSection("metadata") ?: return emptyMap()
        return section.getKeys(false).mapNotNull { key ->
            val value = section.get(key)?.toString()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            key to value
        }.toMap()
    }
}
