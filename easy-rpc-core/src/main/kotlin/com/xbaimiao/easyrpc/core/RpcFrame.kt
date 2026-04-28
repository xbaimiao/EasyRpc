package com.xbaimiao.easyrpc.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal enum class RpcFrameType(val id: Byte) {
    REQUEST(1), RESPONSE(2), ERROR(3);

    companion object {
        fun of(id: Byte): RpcFrameType = entries.firstOrNull { it.id == id }
            ?: error("Unknown RPC frame type: $id")
    }
}

internal data class RpcFrame(
    val type: RpcFrameType,
    val requestId: Long,
    val service: String,
    val group: String,
    val method: String,
    val payload: ByteArray,
    val errorClassifier: String = "",
    val errorMessage: String = "",
)

internal object RpcFrameCodec {
    private const val MAGIC = 0x45525043
    private const val VERSION = 1

    fun encode(frame: RpcFrame): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeByte(VERSION)
            out.writeByte(frame.type.id.toInt())
            out.writeLong(frame.requestId)
            out.writeUTF(frame.service)
            out.writeUTF(frame.group)
            out.writeUTF(frame.method)
            out.writeUTF(frame.errorClassifier)
            out.writeUTF(frame.errorMessage)
            out.writeInt(frame.payload.size)
            out.write(frame.payload)
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): RpcFrame {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = input.readInt()
            require(magic == MAGIC) { "Bad RPC frame magic: $magic" }
            val version = input.readUnsignedByte()
            require(version == VERSION) { "Unsupported RPC frame version: $version" }
            val type = RpcFrameType.of(input.readByte())
            val requestId = input.readLong()
            val service = input.readUTF()
            val group = input.readUTF()
            val method = input.readUTF()
            val errorClassifier = input.readUTF()
            val errorMessage = input.readUTF()
            val payloadSize = input.readInt()
            require(payloadSize >= 0) { "Negative RPC payload size: $payloadSize" }
            val payload = ByteArray(payloadSize)
            input.readFully(payload)
            return RpcFrame(type, requestId, service, group, method, payload, errorClassifier, errorMessage)
        }
    }
}
