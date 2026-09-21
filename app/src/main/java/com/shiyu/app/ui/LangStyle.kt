package com.shiyu.app.ui

import androidx.annotation.ColorRes
import com.shiyu.app.R
import com.shiyu.app.core.lang.LangId

/**
 * 语言在 UI 上的两个表现：AI 语伴的名字、点缀色。
 *
 * 放在 ui/ 而不是 core/lang/：这是纯展示层的约定（换个名字/换个颜色不影响任何逻辑），
 * 而且 SourcePolicyTest 禁止 ui/ 出现语言专有符号，这里只按 LangId 分发，不碰任何语种细节。
 */
object LangStyle {

    /**
     * 语伴名。**两门语言都用 yoyo** —— 用户明确要求统一成一个名字：
     * 这是同一个产品里的同一个 AI 语伴，换语言就换名字反而割裂。
     */
    fun partnerName(lang: LangId): String = "yoyo"

    @ColorRes
    fun accentRes(lang: LangId): Int = when (lang) {
        LangId.JA -> R.color.lang_ja
        LangId.EN -> R.color.lang_en
    }
}
