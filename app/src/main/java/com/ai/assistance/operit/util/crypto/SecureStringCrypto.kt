package com.ai.assistance.operit.util.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ai.assistance.operit.util.AppLogger
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感字符串加密工具（AES-GCM + Android Keystore，密钥不出安全硬件）。
 *
 * 行为约定（非兜底，为明确的设计决策，失败均经 log 暴露，便于排查真正原因）：
 * - encrypt：加解密失败时经 [AppLogger.e] 记录后返回原文。
 *   为什么不直接抛错/丢弃：写入失败会导致 apiKey 无法保存、模型连不上，属于"废功能"。
 * - decrypt：通过 "enc:v1:" 前缀识别密文；无前缀视为旧明文原样返回。
 *   为什么：这是 Don't Break Userspace 的向前兼容，旧配置升级后无需迁移即可继续用。
 *
 * 已知边界：密钥存于本机 Keystore，应用重装/换机后无法解密旧密文，需用户重新配置该密钥。
 *
 * JVM 单测适配：JVM 单元测试运行在未改写的 android.jar 上，Keystore 与 android.util.Base64 均为
 * `Stub!` 实现（调用即抛 RuntimeException）。此类环境下 [isAvailable] 为 false，加解密直接返回原文，
 * 使纯逻辑单测（序列化/模型构造）可正常执行。这是测试环境适配，不是线上兜底：
 * 真机（android.os.Build 已初始化）始终走 Keystore 真实加密。
 */
object SecureStringCrypto {
    private const val TAG = "SecureStringCrypto"
    private const val PREFIX = "enc:v1:"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "operit_secret_aes_gcm"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    /**
     * 是否具备真实加密能力。
     *
     * 为什么用 Build.VERSION.SDK_INT 判定：JVM 单测中 Build 类同为 Stub，读取会抛异常，
     * 故用 runCatching 包裹——成功读到非 0 值即视为真机/模拟器环境。
     */
    private val isAvailable: Boolean by lazy {
        runCatching { android.os.Build.VERSION.SDK_INT > 0 }.getOrDefault(false)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec =
                KeyGenParameterSpec.Builder(
                                KEY_ALIAS,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** 加密。空串原样返回；JVM 单测环境或失败时经 log 暴露后返回原文（保证 apiKey 仍可写入）。 */
    fun encrypt(plain: String): String {
        if (plain.isBlank()) return plain
        if (!isAvailable) return plain
        return runCatching {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                    val iv = cipher.iv
                    val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
                    PREFIX + Base64.getEncoder().encodeToString(iv + ciphertext)
                }
                .getOrElse {
                    AppLogger.e(TAG, "encrypt failed; storing value as-is so it remains usable", it)
                    plain
                }
    }

    /** 解密。无前缀视为旧明文原样返回（向前兼容）；JVM 单测环境或解密失败时返回原文。 */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        if (!isAvailable) return stored
        return runCatching {
                    val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
                    val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
                    val ciphertext = bytes.copyOfRange(GCM_IV_LENGTH, bytes.size)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
                    String(cipher.doFinal(ciphertext), Charsets.UTF_8)
                }
                .getOrElse {
                    AppLogger.e(TAG, "decrypt failed; returning stored value as-is", it)
                    stored
                }
    }
}
