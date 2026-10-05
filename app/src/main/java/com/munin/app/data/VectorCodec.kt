package com.munin.app.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

object VectorCodec {
    fun encode(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(v)
        return buf.array()
    }

    fun decode(b: ByteArray): FloatArray {
        val out = FloatArray(b.size / 4)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }
}
