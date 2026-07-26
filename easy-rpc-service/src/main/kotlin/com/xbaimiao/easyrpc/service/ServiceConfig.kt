package com.xbaimiao.easyrpc.service

import com.xbaimiao.easyrpc.core.RpcAuth
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

data class ServiceConfig(
    val host: String,
    val port: Int,
    val nodeId: String,
    /** service 节点显示名称，留空时回退成 [nodeId]。 */
    val displayName: String? = null,
    /** 共享鉴权 token，留空表示不启用鉴权。 */
    val authToken: String = "",
)

private const val DEFAULT_HOST = "0.0.0.0"
private const val DEFAULT_PORT = 29090
private const val DEFAULT_NODE_ID = "service"
private const val CONFIG_FILE = "easyrpc-service.yml"

fun loadServiceConfig(configPath: Path = Path.of(CONFIG_FILE)): ServiceConfig {
    ensureConfigFile(configPath)
    val fileConfig = loadYamlConfig(configPath) ?: emptyMap()
    val env = System.getenv()

    fun resolve(envKey: String, yamlKey: String, default: String): String {
        return env[envKey]
            ?: fileConfig[yamlKey]?.toString()
            ?: default
    }

    val host = resolve("EASYRPC_HOST", "host", DEFAULT_HOST)
    val port = resolve("EASYRPC_PORT", "port", DEFAULT_PORT.toString()).toInt()
    val nodeId = resolve("EASYRPC_NODE_ID", "node-id", DEFAULT_NODE_ID)
    val displayName = resolve("EASYRPC_DISPLAY_NAME", "display-name", "").trim().takeIf { it.isNotEmpty() }
    val authToken = resolve("EASYRPC_AUTH_TOKEN", "auth-token", "").trim()

    return ServiceConfig(host, port, nodeId, displayName, authToken)
}

private fun ensureConfigFile(path: Path) {
    if (Files.isRegularFile(path)) return
    // 首次生成时随机一个 token，让鉴权默认就是开着的。
    // 如果默认留空，绝大多数部署会一直空着跑，等于没有这个功能。
    val generatedToken = RpcAuth.generateToken()
    val content = """
        # EasyRpc Service 配置文件
        # 环境变量优先级高于此文件:
        #   EASYRPC_HOST  -> host
        #   EASYRPC_PORT  -> port
        #   EASYRPC_NODE_ID -> node-id
        #   EASYRPC_DISPLAY_NAME -> display-name
        #   EASYRPC_AUTH_TOKEN -> auth-token
        host: $DEFAULT_HOST
        port: $DEFAULT_PORT
        node-id: $DEFAULT_NODE_ID
        # 显示名称，留空则回退成 node-id
        display-name: ''
        # 共享鉴权 token。所有 client 的 auth-token 必须和这里一致。
        # 首次启动已随机生成，把它复制到每个 client 的配置里。
        # 留空表示不启用鉴权，任何能连上端口的节点都能接入，只建议本机开发时这么做。
        auth-token: '$generatedToken'
    """.trimIndent()
    path.parent?.let { Files.createDirectories(it) }
    Files.writeString(path, content)
}

private fun loadYamlConfig(path: Path): Map<String, Any>? {
    if (!Files.isRegularFile(path)) return null
    return runCatching {
        val yaml = Yaml()
        @Suppress("UNCHECKED_CAST")
        Files.newBufferedReader(path).use { yaml.load(it) as? Map<String, Any> }
    }.getOrNull()
}
