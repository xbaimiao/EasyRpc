package com.xbaimiao.easyrpc.core

class RpcException(
    val classifier: String,
    override val message: String,
    override val cause: Throwable? = null,
) : RuntimeException(message, cause)

class RpcTimeoutException(message: String) : RuntimeException(message)
