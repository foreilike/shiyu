package com.langpartner.app.data.model

/**
 * AiError / AiResult 的真实声明在 com.langpartner.app.ai（§5.2 的接口签名所在包）。
 * 这里保留类型别名，使 data.model 与 ai 两个路径都可引用（非嵌套用法）。
 */
typealias AiError = com.langpartner.app.ai.AiError
typealias AiResult<T> = com.langpartner.app.ai.AiResult<T>
