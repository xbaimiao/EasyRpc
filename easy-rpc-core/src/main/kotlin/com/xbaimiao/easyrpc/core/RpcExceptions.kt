package com.xbaimiao.easyrpc.core

/**
 * 业务可识别的 RPC 错误。
 *
 * handler 中抛出这个异常时，对端会收到 ERROR frame，并保留 classifier。
 * classifier 建议使用稳定机器可读值，例如 duplicate、offline、not_found。
 */
class RpcException(
    val classifier: String,
    override val message: String,
    override val cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 单响应调用超时。 */
class RpcTimeoutException(message: String) : RuntimeException(message)
