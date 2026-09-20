package com.langpartner.app.core.answer

import com.langpartner.app.core.lang.LanguageModule

data class JudgeResult(val correct: Boolean, val normalizedInput: String, val reason: String?)

/**
 * 作答判定（§5.7）。用户输入与标准答案都先经语言模块归一化再比较；
 * 大小写、全角半角、片假名平假名、长音符形式、空白、标点都不影响判定；
 * 不做任何模糊匹配 / 编辑距离。
 */
object AnswerJudge {

    fun judge(module: LanguageModule, expected: String, userInput: String): JudgeResult {
        val normalizedExpected = module.normalizeForCompare(expected)
        val normalizedInput = module.normalizeForCompare(userInput)
        val correct = normalizedInput == normalizedExpected
        return JudgeResult(
            correct = correct,
            normalizedInput = normalizedInput,
            reason = if (correct) null else "输入：$normalizedInput，期望：$normalizedExpected"
        )
    }
}
