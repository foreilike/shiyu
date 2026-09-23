package com.shiyu.app.data.store

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 云端 TTS 音频的磁盘 LRU 缓存（§17.17 Tier 1）。
 *
 * **为什么要它**：云端 TTS 非流式，单假名也要等 ~1.4s（首字节≈总耗时）。
 * 复习/发音表大量重复朗读同一批词，按 `sha256(model|voice|text)` 落盘后，
 * 命中直接播、秒开且省 API。
 *
 * 纯 JVM（无 android 依赖），目录由调用方指定（App 侧是 `cacheDir/tts_cache`）。
 * LRU 口径：`lastModified`，`get` 命中即 touch；超出 [maxBytes] 时从最旧开始删。
 *
 * 注意：缓存是 cacheDir 下的普通文件，「清空全部数据」不会动它（与现有约定一致，
 * cacheDir 由系统在磁盘紧张时自行清理）。
 */
class TtsDiskCache(private val dir: File, private val maxBytes: Long) {

    init {
        dir.mkdirs()
    }

    /** 缓存键：模型 + 音色 + 文本 三要素，换任一项都会重新合成（不会串音色）。 */
    fun key(text: String, model: String, voice: String): String =
        sha256("$model|$voice|$text")

    /** 命中返回音频文件（并 touch 供 LRU）；未命中 / 空文件返回 null。 */
    @Synchronized
    fun get(key: String): File? {
        val f = fileFor(key)
        if (!f.isFile || f.length() == 0L) return null
        // touch 供 LRU 淘汰：刚用过的不应该先被删
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    /**
     * 写入并按需淘汰。空字节不落盘（服务端返回空体属于错误，别把坏结果缓存住）。
     * 返回落盘后的文件；写入失败返回 null（调用方回落即可，不该因此崩）。
     */
    @Synchronized
    fun put(key: String, bytes: ByteArray): File? {
        if (bytes.isEmpty()) return null
        val target = fileFor(key)
        val tmp = File(dir, "$key.tmp")
        return try {
            // 原子写：先 tmp 再 rename 直接覆盖（与 AtomicFile 同一思路，不留删除窗口）
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                target.writeBytes(bytes)
            }
            evictIfNeeded()
            target
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    /** 当前占用字节数（测试/诊断用）。 */
    @Synchronized
    fun sizeBytes(): Long = entries().sumOf { it.length() }

    private fun fileFor(key: String) = File(dir, "$key.mp3")

    private fun entries(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".mp3") }?.sortedBy { it.name }.orEmpty()

    /** 超出上限时从 lastModified 最旧开始删，删到回到上限内为止。 */
    private fun evictIfNeeded() {
        if (maxBytes <= 0L) return
        var total = entries().sumOf { it.length() }
        if (total <= maxBytes) return
        val oldestFirst = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp3") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        for (f in oldestFirst) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    companion object {
        fun sha256(s: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            return d.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        }
    }
}
