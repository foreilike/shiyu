package com.shiyu.app.data.secret

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.shiyu.app.data.store.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore + AES-256-GCM 实现（§5.10）。
 * 文件格式：[1 byte 版本=1][12 byte IV][密文]，路径 filesDir/secure_settings.bin。
 * 文件不存在/密钥丢失/解密失败 → read() 返回 null（调用方回退默认值，不得崩溃）；
 * 连续失败 3 次后删除文件并删除密钥，下次写入时重建。
 */
class KeystoreSecretStore(context: Context) : SecretStore {

    private val file = File(context.filesDir, "secure_settings.bin")
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Volatile
    private var consecutiveFailures = 0

    @Synchronized
    override fun read(): String? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 1 + IV_LENGTH) {
                onFailure()
                return null
            }
            val version = bytes[0]
            if (version != VERSION) {
                onFailure()
                return null
            }
            val iv = bytes.copyOfRange(1, 1 + IV_LENGTH)
            val cipherText = bytes.copyOfRange(1 + IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val plain = String(cipher.doFinal(cipherText), Charsets.UTF_8)
            consecutiveFailures = 0
            plain
        } catch (e: Exception) {
            onFailure()
            null
        }
    }

    @Synchronized
    override fun write(plain: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + iv.size + cipherText.size)
        out[0] = VERSION
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(cipherText, 0, out, 1 + iv.size, cipherText.size)
        // 原子写：原先直接截断整写，写一半被杀就得到半截密文，
        // 下次 read 判为失败 —— 而失败恢复逻辑又是死的（见下），配置等于永久丢失。
        AtomicFile.writeBytes(file, out)
        consecutiveFailures = 0
    }

    @Synchronized
    override fun clear() {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            // 忽略
        }
        try {
            keyStore.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            // 忽略
        }
        consecutiveFailures = 0
    }

    /**
     * 解密失败的处理。
     *
     * **这里原先是"连续失败 3 次就重建"的计数恢复，实测是死代码**：
     * 计数器是内存字段（每次冷启动归零），而 `SettingsRepository` 又在进程内缓存了
     * 读取结果，`read()` 一个进程生命周期里只会被调用一次 —— 计数器永远到不了 3。
     * 结果是：Keystore 密钥失效（应用数据被克隆/恢复到新机、系统安全更新丢了条目）之后，
     * 每次读都失败、界面显示"配置凭空消失"，而注释里承诺的自愈**从来不会发生**。
     *
     * 现在改成**立即隔离并重建**：解不开的密文在密码学上已不可恢复，留着它只会让人
     * 永远停在"读不出来"的状态。隔离而不是删除 —— 原始密文保留成 `.corrupt-*` 供排查，
     * 密钥删掉，下次写入重建一把新的。用户看到的是"配置被重置"，而不是"配置坏了但没提示"。
     */
    private fun onFailure() {
        consecutiveFailures += 1
        try {
            AtomicFile.quarantine(file)
        } catch (e: Exception) {
            // 忽略
        }
        try {
            keyStore.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            // 忽略
        }
        consecutiveFailures = 0
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "shiyu_settings_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val IV_LENGTH = 12
    }
}
