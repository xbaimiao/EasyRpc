package com.xbaimiao.easyrpc.client

import com.xbaimiao.easyrpc.core.RpcCaller
import com.xbaimiao.easyrpc.core.RpcConnection
import com.xbaimiao.easyrpc.core.RpcEndpoint
import com.xbaimiao.easyrpc.dsl.RpcMethod
import com.xbaimiao.easyrpc.tcp.TcpPacketIo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class TcpRpcClient(
    private val host: String,
    private val port: Int,
    nodeName: String = "client",
    private val connectTimeoutMillis: Int = 5000,
) : RpcCaller, AutoCloseable {
    val endpoint = RpcEndpoint(nodeName)
    private val running = AtomicBoolean(false)
    private lateinit var socket: Socket
    private lateinit var connection: ClientConnection

    fun connect(): TcpRpcClient {
        check(running.compareAndSet(false, true)) { "RPC client already connected" }
        socket = Socket().also { it.connect(InetSocketAddress(host, port), connectTimeoutMillis) }
        connection = ClientConnection(socket)
        Thread(::readLoop, "easy-rpc-client-reader").apply {
            isDaemon = true
            start()
        }
        return this
    }

    override fun <A, R> call(method: RpcMethod<A, R>, args: A, timeout: Duration): CompletableFuture<R> {
        check(running.get()) { "RPC client is not connected" }
        return endpoint.call(connection, method, args, timeout)
    }

    private fun readLoop() {
        try {
            val input = DataInputStream(socket.getInputStream())
            while (running.get() && !socket.isClosed) {
                val packet = TcpPacketIo.read(input) ?: break
                endpoint.receive(connection, packet)
            }
        } finally {
            close()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        endpoint.close()
        runCatching { socket.close() }
    }

    private class ClientConnection(private val socket: Socket) : RpcConnection {
        private val output = DataOutputStream(socket.getOutputStream())
        override val remoteName: String = socket.remoteSocketAddress.toString()
        override fun send(packet: ByteArray) = TcpPacketIo.write(output, packet)
        override fun close() = runCatching { socket.close() }.let { Unit }
    }
}
