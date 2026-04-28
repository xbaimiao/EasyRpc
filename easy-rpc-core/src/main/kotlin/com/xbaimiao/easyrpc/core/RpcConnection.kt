package com.xbaimiao.easyrpc.core

interface RpcConnection : AutoCloseable {
    val remoteName: String
    fun send(packet: ByteArray)
    override fun close()
}

data class RpcSource(val name: String)
