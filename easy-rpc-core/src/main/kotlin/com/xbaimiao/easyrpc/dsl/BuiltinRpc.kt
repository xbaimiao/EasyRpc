package com.xbaimiao.easyrpc.dsl

import com.xbaimiao.easyrpc.codec.RpcCodecs

/**
 * EasyRpc 内置 RPC，service 默认自动注册。
 *
 * client 也可以直接 import 这个 object 来调用，无需重新声明。
 */
object BuiltinRpc : RpcGroup("builtin") {
    val PING = rpc("ping")
        .param(RpcCodecs.STRING)
        .returns(RpcCodecs.STRING)
}
