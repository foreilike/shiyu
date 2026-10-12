package com.shiyu.app.core.lang

/**
 * 实时语音模式的 system prompt。
 *
 * **历史（这里踩过两个坑，别再走回去）**：
 *
 * 1. 不能用 `scenarios_*.json` 里的 `systemPrompt`：那是给**回合制文字对话**写的，
 *    要求模型按 `[JA] / [ZH] / [FIX]` 三段式输出，文字侧由 `ReplyParser` 拆开渲染。
 *    但语音模式下模型是**把整段念出来**的 —— 每句先念一声「JA」、再把中文翻译也读一遍、
 *    纠错也读出来，整段又长又吵；而且提示词通篇中文，模型跟着说中文。
 *
 * 2. 也不能拿场景去"引导"实时语音：实测这样会变成**问卷**。
 *    起初实时语音固定进「自我介绍」场景，提示词里又写着
 *    「自然地创造机会让他说出这些表达」，而该场景的目标表达第一条就是 `My name is ...`
 *    —— 结果**每次开聊都问名字**，两个语言都一样（日语两次会话开场都是「おはようございます」）。
 *    见 HANDOVER §17.24。
 *
 * **所以实时语音走「自由聊天」**：不绑场景、不给目标表达，只描述"怎么像人一样聊天"。
 * 场景练习仍由「对话」页的文字对话承担（那条路用场景自己的 systemPrompt）。
 */
object VoicePrompts {

    /**
     * 自由聊天的 system prompt。
     *
     * @param lang 目标语言，决定"用什么语言说话"以及提示词的措辞
     */
    fun forFreeTalk(lang: LangId): String {
        val t = lang.displayName
        val b = StringBuilder()
        b.append("你是一位").append(t).append("口语陪练，正在和学习者**自由聊天**（不限定话题）。\n\n")
        b.append("说话规则（务必遵守）：\n")
        b.append("1. **默认只用").append(t).append("说话。**\n")
        b.append("2. **但学习者明确要求你用中文时（例如说「翻译一下」「用中文说」「解释一下意思」），")
        b.append("就直接按他说的用中文回答。** 不要反问「可以吗」、不要请他再说一遍、")
        b.append("不要反复确认 —— 直接照做，一两句说完。\n")
        b.append("   ⚠️ 注意：这条优先于第 1 条。若把第 1 条理解成「任何情况下都不许说中文」，")
        b.append("你会陷入不停反问你到底想怎么样，对方会觉得你没在听他说话。\n")
        b.append("3. **像朋友闲聊一样，每次只说 1~2 句，说完停下来等对方回应。**\n")
        b.append("4. **话题跟着对方走，并且要经常换。** 一个话题聊两三轮就自然地转到别处 —— ")
        b.append("今天过得怎么样、天气、吃了什么、周末打算、最近在忙什么、喜欢的东西、")
        b.append("身边发生的趣事……都可以聊。不要死守一个话题反复问。\n")
        b.append("5. **严禁反复提问同一个问题。** 尤其不要每次都从")
        b.append("「你叫什么名字」「你从哪里来」「你几岁」这类**自我介绍**问题开始 —— ")
        b.append("那是问卷，不是聊天。只有对方主动提到名字/来历时，才可以顺着聊；")
        b.append("否则直接聊别的。已经聊过的内容不要再问一遍。\n")
        b.append("6. 用简单常用的词句，学习者水平不高，语速放自然即可。\n")
        b.append("7. **不要念出任何格式标记、标签、翻译或纠错文字。** ")
        b.append("你嘴里应该只有").append(t).append("本身，不要出现方括号、代号或书面说明。\n")
        b.append("8. 对方说中文时，先照第 2 条判断：他是要你翻译/解释，还是在正常聊天。")
        b.append("正常聊天就用").append(t).append("接话（可以先把他的意思用")
        b.append(t).append("简单复述一遍再继续）。")
        return b.toString()
    }

    /**
     * 自由聊天的「提示」内容：给卡壳的学习者一个**话题方向**。
     *
     * 以前这里的提示是场景的 `goalExpressions`，绑了场景就是"该练哪句"；
     * 自由聊天没有目标表达，学习者真正需要的是"能说什么"。
     * 返回「目标语言 / 中文」成对，由 UI 拼成一行。
     */
    fun freeTalkHints(lang: LangId): List<Pair<String, String>> = when (lang) {
        LangId.JA -> listOf(
            "今日はいい天気ですね。" to "今天天气真好。",
            "週末は何をしましたか。" to "周末做了什么？",
            "最近、何か面白いことはありましたか。" to "最近有什么有意思的事吗？",
            "日本の食べ物で何が好きですか。" to "你喜欢什么日本食物？",
            "休みの日は何をしていますか。" to "休息日你都做什么？",
            "最近見た映画はありますか。" to "最近看过什么电影吗？",
        )

        LangId.EN -> listOf(
            "The weather is nice today, isn't it?" to "今天天气真好，不是吗？",
            "What did you do last weekend?" to "你上周末做了什么？",
            "Have you done anything fun recently?" to "最近有什么好玩的事吗？",
            "What kind of food do you like?" to "你喜欢什么食物？",
            "What do you usually do on your days off?" to "休息日你一般做什么？",
            "Have you seen any good movies lately?" to "最近看过什么好电影吗？",
        )
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
