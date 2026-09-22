package com.shiyu.app.data.asset

import android.content.Context
import com.shiyu.app.core.lang.LanguageModule

/**
 * 资产仓库：从 assets 读取六份 JSON 原文（解析由语言模块负责）。
 *
 * **带缓存**：assets 在运行期是不可变的，但调用点会反复来取 —— 首页每次 onResume、
 * 课程页每个语种、历史列表每一行都会取一次。不加缓存的话每次都要读盘 + 解析 JSON
 * （n5_vocab 16KB / en_words 22KB），而 `buildDeck` 这类解析是 O(条目数) 的。
 *
 * 六份资产加起来约 55KB 文本，常驻内存的代价可以忽略。
 */
class AssetRepository(private val context: Context) {

    private val cache = mutableMapOf<String, String>()

    fun chartJson(module: LanguageModule): String = readAsset(module.assetNames().first)

    fun deckJson(module: LanguageModule): String = readAsset(module.assetNames().second)

    /** 按词书 asset 名取牌堆原文（多词书：当前书的 asset）。 */
    fun deckJson(assetName: String): String = readAsset(assetName)

    fun scenariosJson(module: LanguageModule): String = readAsset(module.assetNames().third)

    private fun readAsset(name: String): String = cache.getOrPut(name) {
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
