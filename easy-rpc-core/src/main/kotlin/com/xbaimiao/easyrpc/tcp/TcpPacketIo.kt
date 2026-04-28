package com.xbaimiao.easyrpc.tcp

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

object TcpPacketIo {
    private const val MAX_PACKET_SIZE = 16 * 1024 * 1024

    fun read(input: DataInputStream): ByteArray? {
        val size = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        require(size in 0..MAX_PACKET_SIZE) { "Bad RPC packet size: $size" }
        return ByteArray(size).also(input::readFully)
    }

    fun write(output: DataOutputStream, packet: ByteArray) {
        require(packet.size <= MAX_PACKET_SIZE) { "RPC packet too large: ${packet.size}" }
        synchronized(output) {
            output.writeInt(packet.size)
            output.write(packet)
            output.flush()
        }
    }
}
