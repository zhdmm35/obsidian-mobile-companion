package com.obsidiancompanion.data.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * GitHub Token 安全存储（§10）：
 * - AndroidKeyStore 内生成 AES-256/GCM 密钥，密钥不出安全硬件/Keystore；
 * - 磁盘上只落 Base64(IV) + Base64(密文)（私有 SharedPreferences）；
 * - Token 绝不写入 Room / DataStore / 日志 / crash 信息。
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("credential_store", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun saveToken(token: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ct = Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(KEY_IV, iv).putString(KEY_CT, ct).apply()
    }

    /** 解密失败（密钥失效/数据损坏）返回 null，视为未登录，用户重新输入。 */
    fun getToken(): String? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val ct = prefs.getString(KEY_CT, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun clearToken() {
        prefs.edit().remove(KEY_IV).remove(KEY_CT).apply()
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "obsidian_companion_token_key"
        const val KEY_IV = "token_iv"
        const val KEY_CT = "token_ct"
    }
}
