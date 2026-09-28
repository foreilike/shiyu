package com.shiyu.app.core.audio

/**
 * 「用户这一句」的采集音频累积器（纯 JVM，可单测）。
 *
 * 用途：实时语音要把用户说的话**另送一次**语音识别，好拿到正确语种的转写
 * （stepfun realtime 的转写头只按中英训练，日语会输出中文谐音 —— §17.21.5 B′）。
 *
 * 设计要点：
 * - **只保留最近 [maxBytes]**：正常一句话远小于上限；但若服务端 VAD 一直不报
 *   「说完」，累积器会因为采集不停而无限增长 —— 上限保证内存不会失控，
 *   代价是极端情况下丢掉最早的一段（可接受，因为那也不是刚说的这句）。
 * - [drain] 取值即清空，语义是「把这一句交出去」，调用方不必再手动 clear。
 */
class PcmUtteranceBuffer(private val maxBytes: Int = DEFAULT_MAX_BYTES) {

    private var buf = ByteArray(0)

    /** 当前累积的字节数 */
    val sizeBytes: Int get() = buf.size

    /** 追加一帧 PCM16LE（来自采集回调，可能非 2 字节对齐时以偶数长度截断） */
    @Synchronized
    fun offer(frame: ByteArray) {
        if (frame.isEmpty()) return
        buf = if (buf.isEmpty()) frame.copyOf() else buf + frame
        if (buf.size > maxBytes) {
            // 丢最早的一段，保留最近的
            buf = buf.copyOfRange(buf.size - maxBytes, buf.size)
        }
    }

    /** 取出累积的全部音频并清空 */
    @Synchronized
    fun drain(): ByteArray {
        val out = buf
        buf = ByteArray(0)
        return out
    }

    @Synchronized
    fun clear() {
        buf = ByteArray(0)
    }

    companion object {
        /**
         * 上限 30 秒。实时链路是 24kHz/单声道/16bit → 48000 字节/秒 → 约 1.4MB。
         */
        const val DEFAULT_MAX_BYTES = 30 * 24_000 * 2
    }
}
