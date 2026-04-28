package com.xbaimiao.easyrpc.codec

import com.google.protobuf.MessageLite
import java.io.DataInput
import java.io.DataOutput

interface RpcCodec<T> {
    fun encode(out: DataOutput, value: T)
    fun decode(input: DataInput): T
}

object RpcCodecs {
    val UNIT: RpcCodec<Unit> = object : RpcCodec<Unit> {
        override fun encode(out: DataOutput, value: Unit) = Unit
        override fun decode(input: DataInput) = Unit
    }

    val BOOLEAN: RpcCodec<Boolean> = object : RpcCodec<Boolean> {
        override fun encode(out: DataOutput, value: Boolean) = out.writeBoolean(value)
        override fun decode(input: DataInput): Boolean = input.readBoolean()
    }

    val INT: RpcCodec<Int> = object : RpcCodec<Int> {
        override fun encode(out: DataOutput, value: Int) = out.writeInt(value)
        override fun decode(input: DataInput): Int = input.readInt()
    }

    val LONG: RpcCodec<Long> = object : RpcCodec<Long> {
        override fun encode(out: DataOutput, value: Long) = out.writeLong(value)
        override fun decode(input: DataInput): Long = input.readLong()
    }

    val DOUBLE: RpcCodec<Double> = object : RpcCodec<Double> {
        override fun encode(out: DataOutput, value: Double) = out.writeDouble(value)
        override fun decode(input: DataInput): Double = input.readDouble()
    }

    val STRING: RpcCodec<String> = object : RpcCodec<String> {
        override fun encode(out: DataOutput, value: String) = out.writeUTF(value)
        override fun decode(input: DataInput): String = input.readUTF()
    }

    val BYTE_ARRAY: RpcCodec<ByteArray> = object : RpcCodec<ByteArray> {
        override fun encode(out: DataOutput, value: ByteArray) {
            out.writeInt(value.size)
            out.write(value)
        }

        override fun decode(input: DataInput): ByteArray {
            val size = input.readInt()
            require(size >= 0) { "Negative byte array size: $size" }
            return ByteArray(size).also(input::readFully)
        }
    }

    fun <T> list(element: RpcCodec<T>): RpcCodec<List<T>> = object : RpcCodec<List<T>> {
        override fun encode(out: DataOutput, value: List<T>) {
            out.writeInt(value.size)
            value.forEach { element.encode(out, it) }
        }

        override fun decode(input: DataInput): List<T> {
            val size = input.readInt()
            require(size >= 0) { "Negative list size: $size" }
            return List(size) { element.decode(input) }
        }
    }

    fun <T> nullable(valueCodec: RpcCodec<T>): RpcCodec<T?> = object : RpcCodec<T?> {
        override fun encode(out: DataOutput, value: T?) {
            out.writeBoolean(value != null)
            if (value != null) valueCodec.encode(out, value)
        }

        override fun decode(input: DataInput): T? = if (input.readBoolean()) valueCodec.decode(input) else null
    }

    fun <T : MessageLite> protobuf(parser: (ByteArray) -> T): RpcCodec<T> = object : RpcCodec<T> {
        override fun encode(out: DataOutput, value: T) = BYTE_ARRAY.encode(out, value.toByteArray())
        override fun decode(input: DataInput): T = parser(BYTE_ARRAY.decode(input))
    }
}
