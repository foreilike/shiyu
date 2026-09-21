package com.shiyu.app.core.lang

/**
 * 实时语音模式的 system prompt（**不要用场景自带的 systemPrompt**）。
 *
 * 为什么必须分开：`scenarios_*.json` 里的 `systemPrompt` 是给**回合制文字对话**写的，
 * 它要求模型按 `[JA] / [ZH] / [FIX]` 三段式输出，文字侧由 `ReplyParser` 把标签拆开分别渲染。
 * 但语音模式下模型是**把整段念出来**的，于是：
 *
 * - 每句话开头先念一声「JA」——用户听到的是莫名其妙的「T A」；
 * - 紧接着把中文翻译也读一遍；
 * - 纠错内容也读出来，整段对话变得又长又吵；
 * - 提示词通篇是中文，模型跟着用中文回话，学习者说日语它也不切回去。
 *
 * 所以语音模式单独给一份：只描述「怎么说话」，不出现任何格式标记。
 */
object VoicePrompts {

    /**
     * @param lang      目标语言
     * @param titleZh   场景中文名（给模型一点情境感）
     * @param goals     该场景的目标表达，可为空
     */
    fun forScenario(lang: LangId, titleZh: String, goals: List<String>): String {
        val target = lang.displayName
        val builder = StringBuilder()
        builder.append("你是一位").append(target).append("口语陪练，正在和学习者做")
        if (titleZh.isNotBlank()) builder.append("「").append(titleZh).append("」")
        builder.append("这个场景的口语练习。\n\n")
        builder.append("说话规则（务必遵守）：\n")
        builder.append("1. **默认只用").append(target).append("说话。**\n")
        builder.append("2. **但学习者明确要求你用中文时（例如说「翻译一下」「用中文说」「解释一下意思」），")
        builder.append("就直接按他说的用中文回答。** 不要反问「可以吗」、不要请他再说一遍、")
        builder.append("不要反复确认 —— 直接照做，一两句说完。\n")
        builder.append("   ⚠️ 注意：这条优先于第 1 条。若把第 1 条理解成「任何情况下都不许说中文」，")
        builder.append("你会陷入不停反问你到底想怎么样，对方会觉得你没在听他说话。\n")
        builder.append("3. 用").append(target).append("聊天时，每次只说 1~2 句，像真人一样自然，说完停下来等对方回应。\n")
        builder.append("4. 用简单常用的词句，学习者水平不高，语速放自然即可。\n")
        builder.append("5. **不要念出任何格式标记、标签、翻译或纠错文字。** ")
        builder.append("你嘴里应该只有").append(target).append("本身，不要出现方括号、代号或书面说明。\n")
        builder.append("6. 对方说中文时，先照第 2 条判断：他是要你翻译/解释，还是在正常聊天。")
        builder.append("正常聊天就用").append(target).append("接话（可以先把他的意思用")
        builder.append(target).append("简单复述一遍再继续）。")
        if (goals.isNotEmpty()) {
            builder.append("\n这个场景可以引导对方说出这些表达：")
            builder.append(goals.joinToString("、"))
            builder.append("。自然地创造机会让他说出来，但不要机械地逐条提问。")
        }
        return builder.toString()
    }

    /**
     * 语音对话里学习者可以随时要求换语言。
     *
     * **为什么要在客户端判断，而不是交给提示词**：实测 `step-audio-2-mini` 面对"默认说日语"
     * 和"用户要求中文就照做"两条规则时，几乎总是守前者 —— 换了两版措辞都一样。
     * 轻量语音模型对长规则列表的遵循度有限，所以改成：**客户端识别意图 → 重发 session.update
     * 把系统提示里的语言要求直接改掉**。模型看到的不再是"可选规则"，而是"现在就用中文"。
     */
    enum class LanguageMode { TARGET, CHINESE }

    /** 识别学习者这句话是不是在要求换语言；不是在要求就返回 null */
    fun detectLanguageSwitch(text: String): LanguageMode? {
        val t = text.replace(" ", "").replace("　", "")
        if (t.isEmpty()) return null
        val toChinese = listOf(
            "用中文", "说中文", "讲中文", "中文回复", "中文回答", "中文说",
            "用汉语", "说汉语", "翻译一下", "翻译成中文", "中文解释"
        ).any { t.contains(it) }
        if (toChinese) return LanguageMode.CHINESE
        val toTarget = listOf(
            "用日语", "说日语", "日语回复", "日语回答",
            "用英语", "说英语", "英语回复", "英语回答"
        ).any { t.contains(it) }
        if (toTarget) return LanguageMode.TARGET
        return null
    }

    /**
     * 按语言模式改写系统提示；[LanguageMode.TARGET] 时原样返回（即基础提示本身）。
     *
     * 用 [StringBuilder] 拼换行而不是写转义序列 —— 本机跨工具写文件会把 \\n 折成真换行，
     * 把 Kotlin 源码写坏（这个坑在别处已经踩过两次）。
     */
    fun withLanguageMode(base: String, mode: LanguageMode): String {
        if (mode == LanguageMode.TARGET) return base
        val sb = StringBuilder(base)
        sb.append('\n').append('\n')
        sb.append("【临时切换】学习者刚刚明确要求你用中文。")
        sb.append("从现在起，直到他再次要求换回去，你都必须用简体中文回答，")
        sb.append("每次一到两句，不要再夹任何日语。")
        return sb.toString()
    }
}
