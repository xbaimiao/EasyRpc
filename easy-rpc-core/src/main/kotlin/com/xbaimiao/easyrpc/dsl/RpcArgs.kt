package com.xbaimiao.easyrpc.dsl

import com.xbaimiao.easyrpc.codec.RpcCodec
import java.io.DataInput
import java.io.DataOutput

data class Args1<A>(val a: A) {
    companion object {
        fun <A> codec(a: RpcCodec<A>): RpcCodec<Args1<A>> = object : RpcCodec<Args1<A>> {
            override fun encode(out: DataOutput, value: Args1<A>) = a.encode(out, value.a)
            override fun decode(input: DataInput): Args1<A> = Args1(a.decode(input))
        }
    }
}

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
