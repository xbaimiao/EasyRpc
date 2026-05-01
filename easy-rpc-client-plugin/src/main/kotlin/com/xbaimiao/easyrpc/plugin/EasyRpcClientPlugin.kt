package com.xbaimiao.easyrpc.plugin

import com.xbaimiao.easyrpc.client.NettyRpcClient
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

    override fun onEnable() {
        plugin = this
        saveDefaultConfig()
        if (config.getBoolean("connect-on-enable", true)) {
            runCatching { connect() }.onFailure {
                logger.warning("EasyRpc client connect failed: ${it.message}")
            }
        }
    }

    override fun onDisable() {
        disconnect()
        plugin = null
    }

    /** 按 config.yml 启动 Netty RPC client。重复调用会复用已有 client。 */
    fun connect(): NettyRpcClient {
        client?.let { return it }
        val host = config.getString("host", "127.0.0.1") ?: "127.0.0.1"
        val port = config.getInt("port", 29090)
        val nodeId = config.getString("node-id", server.name) ?: server.name
        val tags = config.getStringList("tags")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val rpcClient = NettyRpcClient(host, port, nodeId, tags = tags).connect()
        client = rpcClient
        if (rpcClient.isConnected()) {
            logger.info("EasyRpc client connected to $host:$port as $nodeId")
        } else {
            logger.warning("EasyRpc service is unavailable, client will reconnect to $host:$port as $nodeId")
        }
        return rpcClient
    }

    /** 关闭当前 RPC 连接。 */
    fun disconnect() {
        client?.close()
        client = null
    }
}
