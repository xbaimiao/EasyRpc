package com.xbaimiao.easyrpc.service

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

data class ServiceConfig(
    val host: String,
    val port: Int,
    val nodeId: String,
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

    return ServiceConfig(host, port, nodeId)
}

private fun ensureConfigFile(path: Path) {
    if (Files.isRegularFile(path)) return
    val content = """
        # EasyRpc Service 配置文件
        # 环境变量优先级高于此文件:
        #   EASYRPC_HOST  -> host
        #   EASYRPC_PORT  -> port
        #   EASYRPC_NODE_ID -> node-id
        host: $DEFAULT_HOST
        port: $DEFAULT_PORT
        node-id: $DEFAULT_NODE_ID
    """.trimIndent()
    Files.createDirectories(path.parent)
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
