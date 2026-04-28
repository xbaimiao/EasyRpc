package com.xbaimiao.easyrpc.plugin

import com.xbaimiao.easyrpc.client.TcpRpcClient
import org.bukkit.plugin.java.JavaPlugin

class EasyRpcClientPlugin : JavaPlugin() {
    companion object {
        private var plugin: EasyRpcClientPlugin? = null

        fun instance(): EasyRpcClientPlugin = plugin ?: error("EasyRpcClientPlugin is not enabled")
        fun clientOrNull(): TcpRpcClient? = plugin?.client
        fun client(): TcpRpcClient = clientOrNull() ?: error("EasyRpc client is not connected")
    }

    private var client: TcpRpcClient? = null

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

    fun connect(): TcpRpcClient {
        client?.let { return it }
        val host = config.getString("host", "127.0.0.1") ?: "127.0.0.1"
        val port = config.getInt("port", 29090)
        val nodeName = config.getString("node-name", server.name) ?: server.name
        return TcpRpcClient(host, port, nodeName).connect().also {
            client = it
            logger.info("EasyRpc client connected to $host:$port")
        }
    }

    fun disconnect() {
        client?.close()
        client = null
    }
}
