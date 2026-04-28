package com.xbaimiao.easyrpc.plugin

import com.xbaimiao.easyrpc.client.NettyRpcClient
import org.bukkit.plugin.java.JavaPlugin

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

    fun connect(): NettyRpcClient {
        client?.let { return it }
        val host = config.getString("host", "127.0.0.1") ?: "127.0.0.1"
        val port = config.getInt("port", 29090)
        val nodeId = config.getString("node-id", server.name) ?: server.name
        return NettyRpcClient(host, port, nodeId).connect().also {
            client = it
            logger.info("EasyRpc client connected to $host:$port as $nodeId")
        }
    }

    fun disconnect() {
        client?.close()
        client = null
    }
}
