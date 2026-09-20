package com.langpartner.app.core.lang.ja

import com.langpartner.app.data.model.Scenario
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * scenarios_*.json 解析（与语言无关，两个模块共用）。
 * 损坏字段用缺省值跳过，不抛异常。
 */
internal object ScenarioParser {

    fun parse(json: String): List<Scenario> {
        val arr: JSONArray = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return emptyList()
        }
        val out = mutableListOf<Scenario>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val goals = mutableListOf<String>()
            val goalArr = o.optJSONArray("goalExpressions")
            if (goalArr != null) {
                for (j in 0 until goalArr.length()) {
                    val g = goalArr.optString(j)
                    if (!g.isNullOrEmpty()) goals.add(g)
                }
            }
            out.add(
                Scenario(
                    id = o.optString("id"),
                    titleTarget = o.optString("titleTarget"),
                    titleZh = o.optString("titleZh"),
                    systemPrompt = o.optString("systemPrompt"),
                    openingLine = o.optString("openingLine"),
                    goalExpressions = goals
                )
            )
        }
        return out
    }
}

/** JSONObject 的安全字符串读取（null/缺失 → 空串） */
internal fun JSONObject.str(key: String): String =
    if (isNull(key)) "" else optString(key, "")
