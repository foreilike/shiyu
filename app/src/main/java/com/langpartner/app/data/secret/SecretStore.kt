package com.langpartner.app.data.secret

/**
 * 加密 Secret 存储接口。read() 返回明文 JSON，未初始化/解密失败返回 null。
 * Android 实现见 KeystoreSecretStore（§5.10）。
 */
interface SecretStore {
    fun read(): String?
    fun write(plain: String)
    fun clear()
}
