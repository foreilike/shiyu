package com.shiyu.app.core.audio

/**
 * PCM16LE 字节流与样本数组互转（纯 JVM，可单测）。
 *
 * 存在理由：采集链路给的是 `ByteArray`（直接往 WebSocket 推），
 * 而 [VadPolicy] 的裁剪按 `ShortArray` 样本算；两边都要用，转换得有一处统一实现
 * （尤其**小端**这个约定，写错了裁出来就是噪声）。
 */
object Pcm16 {

    /** 小端字节 → 样本；奇数尾字节丢弃 */
    fun toShorts(bytes: ByteArray): ShortArray {
        val n = bytes.size / 2
        val out = ShortArray(n)
        for (i in 0 until n) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    /** 样本 → 小端字节 */
    fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
