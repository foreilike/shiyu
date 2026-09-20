package com.shiyu.app.core.lang

import com.shiyu.app.core.lang.en.EnModule
import com.shiyu.app.core.lang.ja.JaModule

/**
 * 按 LangId 取语言模块。这是唯一允许按语言分发的入口（§5.6.1 硬性要求：
 * ui/ / ai/ / data/ 不得出现语言专有分支）。
 */
object LanguageRegistry {

    private val modules: Map<LangId, LanguageModule> = mapOf(
        LangId.JA to JaModule(),
        LangId.EN to EnModule()
    )

    fun module(id: LangId): LanguageModule =
        modules[id] ?: error("unsupported lang: $id")

    fun moduleForTag(tag: String?): LanguageModule = module(LangId.fromTag(tag))
}
