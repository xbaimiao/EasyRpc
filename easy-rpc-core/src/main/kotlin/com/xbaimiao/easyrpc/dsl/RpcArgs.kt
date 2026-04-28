package com.xbaimiao.easyrpc.dsl

import com.xbaimiao.easyrpc.codec.RpcCodec
import java.io.DataInput
import java.io.DataOutput

/**
 * 1 参数 RPC 的参数容器。
 *
 * Kotlin 函数类型可以表达 `(A) -> R`，但真正发包时仍然需要把参数组合成一个整体 payload，
 * 所以 DSL 内部用 ArgsN 把多个参数打包，再交给对应 codec 顺序编码。
 */
data class Args1<A>(val a: A) {
    companion object {
        fun <A> codec(a: RpcCodec<A>): RpcCodec<Args1<A>> = object : RpcCodec<Args1<A>> {
            override fun encode(out: DataOutput, value: Args1<A>) = a.encode(out, value.a)
            override fun decode(input: DataInput): Args1<A> = Args1(a.decode(input))
        }
    }
}

/** 2 参数 RPC 的参数容器，编码顺序必须和 builder 声明顺序一致。 */
data class Args2<A, B>(val a: A, val b: B) {
    companion object {
        fun <A, B> codec(a: RpcCodec<A>, b: RpcCodec<B>): RpcCodec<Args2<A, B>> = object : RpcCodec<Args2<A, B>> {
            override fun encode(out: DataOutput, value: Args2<A, B>) {
                a.encode(out, value.a)
                b.encode(out, value.b)
            }
            override fun decode(input: DataInput): Args2<A, B> = Args2(a.decode(input), b.decode(input))
        }
    }
}

/** 3 参数 RPC 的参数容器。 */
data class Args3<A, B, C>(val a: A, val b: B, val c: C) {
    companion object {
        fun <A, B, C> codec(a: RpcCodec<A>, b: RpcCodec<B>, c: RpcCodec<C>): RpcCodec<Args3<A, B, C>> = object : RpcCodec<Args3<A, B, C>> {
            override fun encode(out: DataOutput, value: Args3<A, B, C>) {
                a.encode(out, value.a)
                b.encode(out, value.b)
                c.encode(out, value.c)
            }
            override fun decode(input: DataInput): Args3<A, B, C> = Args3(a.decode(input), b.decode(input), c.decode(input))
        }
    }
}

/** 4 参数 RPC 的参数容器。 */
data class Args4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D) {
    companion object {
        fun <A, B, C, D> codec(a: RpcCodec<A>, b: RpcCodec<B>, c: RpcCodec<C>, d: RpcCodec<D>): RpcCodec<Args4<A, B, C, D>> = object : RpcCodec<Args4<A, B, C, D>> {
            override fun encode(out: DataOutput, value: Args4<A, B, C, D>) {
                a.encode(out, value.a)
                b.encode(out, value.b)
                c.encode(out, value.c)
                d.encode(out, value.d)
            }
            override fun decode(input: DataInput): Args4<A, B, C, D> = Args4(a.decode(input), b.decode(input), c.decode(input), d.decode(input))
        }
    }
}

/**
 * 5 参数 RPC 的参数容器。
 *
 * 继续增加 Args6/Args7 技术上可行，但可读性会变差。
 * 参数很多时建议改成一个 protobuf request message。
 */
data class Args5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E) {
    companion object {
        fun <A, B, C, D, E> codec(a: RpcCodec<A>, b: RpcCodec<B>, c: RpcCodec<C>, d: RpcCodec<D>, e: RpcCodec<E>): RpcCodec<Args5<A, B, C, D, E>> = object : RpcCodec<Args5<A, B, C, D, E>> {
            override fun encode(out: DataOutput, value: Args5<A, B, C, D, E>) {
                a.encode(out, value.a)
                b.encode(out, value.b)
                c.encode(out, value.c)
                d.encode(out, value.d)
                e.encode(out, value.e)
            }
            override fun decode(input: DataInput): Args5<A, B, C, D, E> = Args5(a.decode(input), b.decode(input), c.decode(input), d.decode(input), e.decode(input))
        }
    }
}
