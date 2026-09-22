package com.shiyu.app.core.lang

/**
 * 一本词书 / 一个级别。多本书共用同一门语言的 [LanguageModule]，各自一份 assets JSON，
 * 进度按卡片 id 区分（id 全局唯一，导入时按书前缀，如 `cet4_00001` / `n5_0001`）。
 */
data class WordBook(
    val id: String,
    val langId: LangId,
    val title: String,
    val asset: String
)
