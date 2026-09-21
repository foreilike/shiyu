package com.shiyu.app.data.store

import java.io.File

/**
 * 落盘的原子写与「写一半被打断」的恢复。
 *
 * **为什么要单独抽出来**：原先三个明文 store 各自抄了一份「先 delete 再 rename」，那个
 * delete 是多余的 —— POSIX 的 `rename(2)` 本身就是原子替换，先删反而**打开了"目标文件不
 * 存在"的窗口**：进程恰好在 delete 之后、rename 之前被杀，下次启动就找不到文件，
 * 各 store 的 load 会走「文件不存在 → 返回空结构」分支，用户全部进度/统计**静默清零**。
 * 而 tmp 里明明有完整数据，却没有任何一处会去捡。
 *
 * 现在统一成：写 tmp → rename 直接覆盖（不删）。并且 load 之前先 [recoverIfNeeded]，
 * 把上次写一半留下的 tmp 补回去。
 *
 * 加密 store 也走这里 —— 它原先是最敏感却最不设防的一处（直接截断写，连 tmp 都没有）。
 */
object AtomicFile {

    /**
     * 恢复上一次写一半留下的残局。
     *
     * 情况：目标文件不存在、但同目录下有 `<名字>.tmp` ——
     * 说明上次写到 rename 之前被打断。tmp 里是完整内容，直接认领为主文件。
     * 调用方应该在每次读取之前调用它。
     */
    fun recoverIfNeeded(target: File) {
        if (target.exists()) return
        try {
            val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
            if (tmp.exists() && tmp.length() > 0L) {
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            // 恢复失败不影响主流程：调用方会走"文件不存在"的默认分支
        }
    }

    /**
     * 原子写：先写 `.tmp` 再 rename 覆盖。**不要先 delete 目标文件**（见类注释）。
     *
     * rename 失败时（极少数文件系统/权限问题）退化为直接写。这时确实不原子，
     * 但至少有内容落盘；比"直接放弃导致用户改的东西没保存"要好。
     */
    fun write(target: File, text: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, target.name + TMP_SUFFIX)
        try {
            tmp.writeText(text)
            // rename 直接覆盖，中间不存在"两个都没有"的窗口
            if (tmp.renameTo(target)) return
        } catch (e: Exception) {
            // 落到下面的兜底
        }
        try {
            target.writeText(text)
        } finally {
            try { tmp.delete() } catch (e: Exception) { /* 忽略 */ }
        }
    }

    /** 字节版原子写（加密存储用，密文不是文本） */
    fun writeBytes(target: File, bytes: ByteArray) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, target.name + TMP_SUFFIX)
        try {
            tmp.writeBytes(bytes)
            if (tmp.renameTo(target)) return
        } catch (e: Exception) {
            // 落到下面的兜底
        }
        try {
            target.writeBytes(bytes)
        } finally {
            try { tmp.delete() } catch (e: Exception) { /* 忽略 */ }
        }
    }

    /** 坏文件隔离：改名成 `<名字>.corrupt-<时间戳>`，保留现场供排查 */
    fun quarantine(target: File) {
        try {
            if (!target.exists()) return
            val dest = File(target.parentFile, target.name + ".corrupt-" + System.currentTimeMillis())
            target.copyTo(dest, overwrite = true)
            target.delete()
        } catch (e: Exception) {
            // 隔离失败也不得影响调用方
        }
    }

    private const val TMP_SUFFIX = ".tmp"
}
