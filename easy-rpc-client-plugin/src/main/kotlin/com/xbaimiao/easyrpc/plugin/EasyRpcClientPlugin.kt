package com.xbaimiao.easyrpc.plugin

import com.xbaimiao.easyrpc.client.NettyRpcClient
import com.xbaimiao.easyrpc.core.normalizedTags
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Paper 插件版 RPC client 包装。
 *
 * 这个插件只负责维护一个 [NettyRpcClient] 连接，不承载 service/router。
 * 其它插件可以依赖它，然后通过 [client] 获取 SDK client 发起 RPC 或注册 handler。
 */
class EasyRpcClientPlugin : JavaPlugin(), Listener {
    companion object {
        /** Bukkit 节点自动上报的保留 metadata，供跨服在线人数变量识别和汇总。 */
        internal const val CLIENT_TYPE_METADATA_KEY = "easyrpc-client-type"
        internal const val ONLINE_PLAYERS_METADATA_KEY = "easyrpc-online-players"
        internal const val BUKKIT_CLIENT_TYPE = "bukkit"

        private var plugin: EasyRpcClientPlugin? = null

        fun instance(): EasyRpcClientPlugin = plugin ?: error("EasyRpcClientPlugin is not enabled")
        fun clientOrNull(): NettyRpcClient? = plugin?.client
        fun client(): NettyRpcClient = clientOrNull() ?: error("EasyRpc client is not connected")
    }

    private var client: NettyRpcClient? = null
    private var expansion: EasyRpcExpansion? = null
    private var onlinePlayerCountSyncScheduled = false

    override fun onEnable() {
        plugin = this
        saveDefaultConfig()
        server.pluginManager.registerEvents(this, this)
        if (config.getBoolean("connect-on-enable", true)) {
            runCatching { connect() }.onFailure {
                logger.warning("EasyRpc client connect failed: ${it.message}")
            }
        }
        registerPlaceholders()
        getCommand("easyrpc")?.let {
            val executor = EasyRpcCommand(this)
            it.setExecutor(executor)
            it.tabCompleter = executor
        }
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
        // 注意 getString 的默认值只在键缺失时生效，空字符串会原样返回，所以要自己兜一次。
        val nodeId = config.getString("node-id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: server.name
        val tags = config.getStringList("tags").normalizedTags()
        val displayName = config.getString("display-name")?.trim()?.takeIf { it.isNotEmpty() }
        val metadata = readBukkitMetadata()
        val authToken = config.getString("auth-token")?.trim() ?: ""
        if (authToken.isEmpty()) {
            logger.warning("EasyRpc auth-token is empty; this only works if the service has auth disabled")
        }
        val rpcClient = NettyRpcClient(
            host = host,
            port = port,
            nodeId = nodeId,
            tags = tags,
            displayName = displayName,
            metadata = metadata,
            authToken = authToken,
        )
        rpcClient.onAuthFailure = { reason ->
            logger.severe("EasyRpc auth rejected by service: $reason")
            logger.severe("Fix auth-token in config.yml, then run: /easyrpc reconnect")
        }
        registerConsoleHandler(rpcClient)
        rpcClient.connect()
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
     * 重新读取 config.yml 里的 auth-token 并重连。
     *
     * 这里必须走 [NettyRpcClient.retryAuth] 而不是 disconnect + connect：
     * 其它插件把 handler 注册在当前 client 的 endpoint 上，重建 client 会把这些 handler 全丢掉。
     */
    fun reloadAuth(): String {
        val rpcClient = client ?: return "EasyRpc client 未启动，请使用 /easyrpc connect"
        reloadConfig()
        val newToken = config.getString("auth-token")?.trim() ?: ""
        if (!rpcClient.isAuthRejected()) {
            return if (rpcClient.isConnected()) {
                "EasyRpc 当前已连接，无需重连"
            } else {
                "EasyRpc 正在自动重连中，不是鉴权问题"
            }
        }
        return if (rpcClient.retryAuth(newToken)) {
            "EasyRpc 已用新 token 重新连接，请查看控制台日志确认结果"
        } else {
            "EasyRpc 重连失败，client 可能已关闭"
        }
    }

    /** 是否允许别的节点让本服执行控制台命令。 */
    fun acceptRemoteCommands(): Boolean = config.getBoolean("remote-command.accept", true)

    /** 是否捕获命令输出回传给发起方。 */
    fun captureCommandOutput(): Boolean = config.getBoolean("remote-command.capture-output", true)

    /**
     * 玩家加入或退出事件触发时，事件当下的 Bukkit 在线列表可能仍处于变更过程。
     * 延迟到下一 tick 再读取最终人数，并合并同一 tick 内的多次变更。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        scheduleOnlinePlayerCountSync()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        scheduleOnlinePlayerCountSync()
    }

    private fun scheduleOnlinePlayerCountSync() {
        if (onlinePlayerCountSyncScheduled) return
        onlinePlayerCountSyncScheduled = true
        server.scheduler.runTask(this, Runnable {
            onlinePlayerCountSyncScheduled = false
            syncOnlinePlayerCount()
        })
    }

    /** 人数没有变化时不重发 HELLO，避免产生无意义的全节点在线列表广播。 */
    private fun syncOnlinePlayerCount() {
        val rpcClient = client ?: return
        val onlinePlayers = server.onlinePlayers.size.toString()
        if (rpcClient.metadata[ONLINE_PLAYERS_METADATA_KEY] == onlinePlayers) return
        rpcClient.updateMetadata(ONLINE_PLAYERS_METADATA_KEY, onlinePlayers)
    }

    /**
     * 注册接收端 handler。
     *
     * 默认关闭：远程执行控制台命令等于把本服的完整控制权交给任何持有 token 的节点，
     * 必须由这台服务器的管理员显式打开，不能因为装了插件就自动获得。
     */
    private fun registerConsoleHandler(rpcClient: NettyRpcClient) {
        if (!acceptRemoteCommands()) {
            logger.info("EasyRpc remote-command.accept=false, 本服不接受远程控制台命令")
            return
        }
        logger.warning("EasyRpc remote-command.accept=true, 任何持有相同 token 的节点都可以让本服执行控制台命令")
        ConsoleRpc.RUN.listenAsync(rpcClient) { source, command, requester ->
            // 审计日志。source.nodeId 已由 service 校验过和连接一致，但仍只代表
            // 「某个持有 token 的连接自称是它」，不能当成强身份。
            logger.warning("EasyRpc remote command from ${source.nodeId} ($requester): $command")
            ConsoleCommandRunner.run(this, command, captureCommandOutput())
        }
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

    /** 合并 Bukkit 节点的运行时信息；保留键始终由插件维护，不能被配置覆盖。 */
    private fun readBukkitMetadata(): Map<String, String> = readMetadata().toMutableMap().apply {
        this[CLIENT_TYPE_METADATA_KEY] = BUKKIT_CLIENT_TYPE
        this[ONLINE_PLAYERS_METADATA_KEY] = server.onlinePlayers.size.toString()
    }
}
