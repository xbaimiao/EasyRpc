package com.xbaimiao.easyrpc.plugin

import com.xbaimiao.easyrpc.codec.RpcCodecs
import com.xbaimiao.easyrpc.dsl.RpcGroup

/**
 * 跨服控制台命令 RPC。
 *
 * 参数是要执行的命令和发起方描述（用于被执行方的日志审计）。
 * 返回值是执行结果文本，包含命令产生的控制台输出。
 */
object ConsoleRpc : RpcGroup("easyrpc-console") {
    /** 在目标服务器的控制台执行一条命令。 */
    val RUN = rpc("run")
        .param(RpcCodecs.STRING)
        .param(RpcCodecs.STRING)
        .returns(RpcCodecs.STRING)
}
