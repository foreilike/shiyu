package com.shiyu.app.data.asset

import android.content.Context
import com.shiyu.app.core.lang.LanguageModule

/**
 * 资产仓库：从 assets 读取六份 JSON 原文（解析由语言模块负责）。
 */
class AssetRepository(private val context: Context) {

    fun chartJson(module: LanguageModule): String = readAsset(module.assetNames().first)

    fun deckJson(module: LanguageModule): String = readAsset(module.assetNames().second)

    fun scenariosJson(module: LanguageModule): String = readAsset(module.assetNames().third)

    private fun readAsset(name: String): String =
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
