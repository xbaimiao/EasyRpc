package com.xbaimiao.easyrpc.codec

import com.google.protobuf.MessageLite
import io.netty.buffer.ByteBuf
import java.nio.charset.StandardCharsets

/**
 * RPC 业务参数和返回值的编码器。
 *
 * 外层网络已经基于 Netty，所以业务 payload 也直接使用 [ByteBuf]。
 * 这样自定义 codec 可以直接使用 Netty 的读写 API，也更容易和 Netty 生态结合。
 */
interface RpcCodec<T> {
    fun encode(out: ByteBuf, value: T)
    fun decode(input: ByteBuf): T
}

/** 内置常用 codec。泛型集合必须显式传 element codec，避免运行期类型擦除。 */
object RpcCodecs {
    /** 无返回值或无参数时使用。 */
    val UNIT: RpcCodec<Unit> = object : RpcCodec<Unit> {
        override fun encode(out: ByteBuf, value: Unit) = Unit
        override fun decode(input: ByteBuf) = Unit
    }

    val BOOLEAN: RpcCodec<Boolean> = object : RpcCodec<Boolean> {
        override fun encode(out: ByteBuf, value: Boolean) {
            out.writeBoolean(value)
        }

        override fun decode(input: ByteBuf): Boolean = input.readBoolean()
    }

    val INT: RpcCodec<Int> = object : RpcCodec<Int> {
        override fun encode(out: ByteBuf, value: Int) {
            out.writeInt(value)
        }

        override fun decode(input: ByteBuf): Int = input.readInt()
    }

    val LONG: RpcCodec<Long> = object : RpcCodec<Long> {
        override fun encode(out: ByteBuf, value: Long) {
            out.writeLong(value)
        }

        override fun decode(input: ByteBuf): Long = input.readLong()
    }

    val DOUBLE: RpcCodec<Double> = object : RpcCodec<Double> {
        override fun encode(out: ByteBuf, value: Double) {
            out.writeDouble(value)
        }

        override fun decode(input: ByteBuf): Double = input.readDouble()
    }

    /** UTF-8 字符串，使用 int 长度前缀，不受 Java modified UTF-8 长度限制。 */
    val STRING: RpcCodec<String> = object : RpcCodec<String> {
        override fun encode(out: ByteBuf, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            out.writeInt(bytes.size)
            out.writeBytes(bytes)
        }

        override fun decode(input: ByteBuf): String {
            val size = input.readInt()
            require(size >= 0) { "Negative string size: $size" }
            val bytes = ByteArray(size)
            input.readBytes(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    /** 长度前缀 byte array。 */
    val BYTE_ARRAY: RpcCodec<ByteArray> = object : RpcCodec<ByteArray> {
        override fun encode(out: ByteBuf, value: ByteArray) {
            out.writeInt(value.size)
            out.writeBytes(value)
        }

        override fun decode(input: ByteBuf): ByteArray {
            val size = input.readInt()
            require(size >= 0) { "Negative byte array size: $size" }
            val bytes = ByteArray(size)
            input.readBytes(bytes)
            return bytes
        }
    }

    /** List codec。元素 codec 必须显式传入。 */
    fun <T> list(element: RpcCodec<T>): RpcCodec<List<T>> = object : RpcCodec<List<T>> {
        override fun encode(out: ByteBuf, value: List<T>) {
            out.writeInt(value.size)
            value.forEach { element.encode(out, it) }
        }

        override fun decode(input: ByteBuf): List<T> {
            val size = input.readInt()
            require(size >= 0) { "Negative list size: $size" }
            return List(size) { element.decode(input) }
        }
    }

    /** Nullable codec。先写入一个 boolean 表示值是否存在。 */
    fun <T> nullable(valueCodec: RpcCodec<T>): RpcCodec<T?> = object : RpcCodec<T?> {
        override fun encode(out: ByteBuf, value: T?) {
            out.writeBoolean(value != null)
            if (value != null) valueCodec.encode(out, value)
        }

        override fun decode(input: ByteBuf): T? = if (input.readBoolean()) valueCodec.decode(input) else null
    }

    /** protobuf message codec。parser 通常传 `MyProto.Message::parseFrom`。 */
    fun <T : MessageLite> protobuf(parser: (ByteArray) -> T): RpcCodec<T> = object : RpcCodec<T> {
        override fun encode(out: ByteBuf, value: T) = BYTE_ARRAY.encode(out, value.toByteArray())
        override fun decode(input: ByteBuf): T = parser(BYTE_ARRAY.decode(input))
    }
}
