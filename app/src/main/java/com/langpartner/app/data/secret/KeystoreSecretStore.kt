package com.langpartner.app.data.secret

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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

    override fun write(plain: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + iv.size + cipherText.size)
        out[0] = VERSION
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(cipherText, 0, out, 1 + iv.size, cipherText.size)
        if (!file.exists()) file.createNewFile()
        file.writeBytes(out)
        consecutiveFailures = 0
    }

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

    private fun onFailure() {
        consecutiveFailures += 1
        if (consecutiveFailures >= 3) {
            clear()
        }
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
        private const val KEY_ALIAS = "langpartner_settings_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val IV_LENGTH = 12
    }
}
