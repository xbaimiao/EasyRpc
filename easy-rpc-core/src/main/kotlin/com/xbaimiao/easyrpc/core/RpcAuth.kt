package com.xbaimiao.easyrpc.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** ERROR frame 的鉴权失败分类。client 看到它就知道是 token 不对，而不是普通业务错误。 */
const val RPC_ERROR_UNAUTHORIZED = "unauthorized"

/**
 * 共享 token 鉴权。
 *
 * service 配置一个 token，所有 client 用同一个 token 连接。
 * client 在 HELLO 里带上 token，service 校验通过后按连接记住结果，后续 frame 不再重复携带。
 *
 * token 为空表示不启用鉴权，任何 client 都能连上，适合本机开发。
 *
 * 注意这套机制只防止「连错集群」和「未授权节点接入」，不提供传输加密。
 * token 是明文过线的，所以 service 端口不能暴露到公网，必须放在内网或隧道里。
 */
object RpcAuth {
    /**
     * 定长时间比较两个 token。
     *
     * 不要直接用 `==`：那是短路比较，比较耗时会随匹配前缀长度变化，理论上可以被逐字节爆破。
     */
    fun matches(expected: String, actual: String): Boolean {
        // MessageDigest.isEqual 在长度不同时会立刻返回，但长度本身不是需要保护的秘密。
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8),
        )
    }

    /** 生成一个随机 token，方便运维直接拿去填配置。 */
    fun generateToken(bytes: Int = 32): String {
        require(bytes >= 16) { "Auth token must be at least 16 bytes" }
        val random = ByteArray(bytes)
        SecureRandom().nextBytes(random)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random)
    }

    /** 把 token 脱敏成可以写日志的形式，只保留前 4 位。 */
    fun mask(token: String): String = when {
        token.isEmpty() -> "<none>"
        token.length <= 4 -> "****"
        else -> token.take(4) + "****"
    }
}
