package com.shiyu.app.core.lang

/**
 * 判断一段文字"看起来是哪种文字体系"。
 *
 * **为什么需要**：实时语音改成自动跟随语种之后，模型经常直接用中文回答
 * （用户说中文时就该如此）。而字幕下面那行"中文小字"是另起一次调用翻译出来的 ——
 * 把一句中文再"翻译"成中文，结果就是**同一句话在气泡里印两遍**（真机实测可见）。
 * 所以追加译文之前得先问一句：这句本来是不是就是中文？
 */
object TextScript {

    /**
     * 这段文字是否**已经是中文**（因而不需要再翻译）。
     *
     * 判据：含汉字，且不含假名、谚文与拉丁字母。标点、数字、空白一律不计。
     *
     * ⚠️ 已知边界：**全是汉字的日语**（如「今日」「大丈夫」）会被判成中文，于是跳过翻译。
     * 这是故意的取舍 —— 口语里这种句子极少，而误判的代价只是少一行译文；
     * 反过来放过它就会让每句中文都重复一遍，那是每轮都能看见的。
     */
    fun isChineseOnly(text: String): Boolean {
        var hasHan = false
        for (c in text) {
            val code = c.code
            when {
                // 平假名 / 片假名（含半角片假名）
                code in 0x3040..0x30FF || code in 0xFF66..0xFF9D -> return false
                // 谚文
                code in 0xAC00..0xD7AF || code in 0x1100..0x11FF -> return false
                // 拉丁字母
                c in 'a'..'z' || c in 'A'..'Z' -> return false
                // 汉字（含扩展 A）
                code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF -> hasHan = true
            }
        }
        return hasHan
    }

    /**
     * 这段文字里有没有假名（平假名 / 片假名 / 半角片假名）。
     *
     * 用途是**判定一段话是不是日语**，有两处在用：
     * ① 模型的回复含假名 → 说明用户刚才那句说的是日语（模型判语种走音频，实测可靠），
     *    于是那一句的字幕才值得送去独立 ASR 修；
     * ② 修回来的结果也必须含假名才采用 —— 否则说明 ASR 没真修成日语，保留原文更安全。
     */
    fun hasKana(text: String): Boolean = text.any { c ->
        val code = c.code
        code in 0x3040..0x30FF || code in 0xFF66..0xFF9D
    }
}
