package com.xbaimiao.easyrpc.service

import com.xbaimiao.easyrpc.core.RpcConnection
import com.xbaimiao.easyrpc.core.RpcEndpoint
import com.xbaimiao.easyrpc.tcp.TcpPacketIo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class TcpRpcServer(
    val serviceName: String,
    private val host: String = "0.0.0.0",
    private val port: Int = 29090,
) : AutoCloseable {
    val endpoint = RpcEndpoint(serviceName)
    private val running = AtomicBoolean(false)
    private val connections = Collections.synchronizedSet(mutableSetOf<ClientConnection>())
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start(): TcpRpcServer {
        check(running.compareAndSet(false, true)) { "RPC server already started" }
        socket = ServerSocket().also { it.bind(InetSocketAddress(host, port)) }
        acceptThread = Thread(::acceptLoop, "easy-rpc-server-$port").apply {
            isDaemon = false
            start()
        }
        return this
    }

    fun awaitClose() {
        acceptThread?.join()
    }

    private fun acceptLoop() {
        val serverSocket = socket ?: return
        while (running.get()) {
            val client = runCatching { serverSocket.accept() }.getOrElse { null } ?: break
            val connection = ClientConnection(client)
            connections += connection
            connection.start()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        connections.toList().forEach { it.close() }
        endpoint.close()
    }

    private inner class ClientConnection(private val socket: Socket) : RpcConnection {
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())
        override val remoteName: String = socket.remoteSocketAddress.toString()

        fun start() {
            Thread(::readLoop, "easy-rpc-client-$remoteName").apply {
                isDaemon = true
                start()
            }
        }

        private fun readLoop() {
            try {
                while (running.get() && !socket.isClosed) {
                    val packet = TcpPacketIo.read(input) ?: break
                    endpoint.receive(this, packet)
                }
            } finally {
                close()
            }
        }

        override fun send(packet: ByteArray) = TcpPacketIo.write(output, packet)

        override fun close() {
            connections -= this
            runCatching { socket.close() }
        }
    }
}
