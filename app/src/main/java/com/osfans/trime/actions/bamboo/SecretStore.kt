/*
 * SPDX-FileCopyrightText: 2025 BambooIME
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.actions.bamboo

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import timber.log.Timber

/**
 * 用 Android Keystore 里的 AES-256-GCM 密钥加密短文本，目前只用于 AI 的 API Key。
 *
 * 设计取舍：
 *  * 密钥由系统生成、**不可导出**，也不随备份离开设备。所以恢复备份到新手机后，
 *    旧密文解不开——这正是我们想要的：拿不到 Key 就当作没设置，让用户重填。
 *  * 密文格式 `v1:` + Base64(12 字节 IV ‖ 密文 ‖ tag)。前缀用于识别「已加密」，
 *    也留出了将来换算法/换密钥的空间。
 *  * 解密失败一律返回 null，调用方按「没有 Key」处理；**绝不**把解不开的密文
 *    当明文用，也绝不因为解不开就静默删掉——保留密文，等用户重新填写时覆盖。
 *  * 早期版本把 Key 以明文存在 prefs 里，[decrypt] 对没有前缀的值原样返回，
 *    迁移逻辑见 `BambooPrefs.migratePlaintextKey()`。
 */
object SecretStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "bamboo_secret_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val PREFIX = "v1:"

    fun isEncrypted(value: String?): Boolean = value != null && value.startsWith(PREFIX)

    fun encrypt(plain: String): String? =
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }.onFailure {
            Timber.w(it, "bamboo: encrypt failed")
        }.getOrNull()

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        // 兼容早期明文；迁移会把它们换成密文
        if (!isEncrypted(stored)) return stored
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN),
            )
            String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        }.onFailure {
            Timber.w(it, "bamboo: decrypt failed, treat as not set")
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
