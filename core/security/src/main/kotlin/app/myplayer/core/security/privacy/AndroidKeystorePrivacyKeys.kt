package app.myplayer.core.security.privacy

import android.annotation.TargetApi
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 隐私模式的两把「认证门控」Keystore 钥匙（方案 Y 的 KEK‑BIO / KEK‑DEVICE）。
 *
 * 与普通模式的 `myplayer_credentials` 刻意分离：
 * | | 普通模式 | 隐私 KEK‑BIO | 隐私 KEK‑DEVICE |
 * |---|---|---|---|
 * | 别名 | `myplayer_credentials` | `myplayer_privacy_auth` | `myplayer_privacy_device` |
 * | 用户认证 | 不需要 | **每次使用都要生物识别** | **每次使用都要设备密码** |
 * | 换指纹后 | 不受影响 | 密钥失效（需 PIN 重绑） | 不受影响 |
 *
 * **可用性下限**：
 * - KEK‑BIO：API 26+（26–29 用 `setUserAuthenticationValidityDurationSeconds(-1)` 实现「按次认证」）。
 * - KEK‑DEVICE：**API 30+**。API < 30 的 `BiometricPrompt` 不支持「设备密码 + CryptoObject」，
 *   因此走不到「用设备密码解封」，只能退化为「设备密码确认后清数据重设」（见方案 §P0#5）。
 */
object AndroidKeystorePrivacyKeys {

    const val BIOMETRIC_ID = "bio"
    const val DEVICE_ID = "device"

    const val ALIAS_BIOMETRIC = "myplayer_privacy_auth"
    const val ALIAS_DEVICE = "myplayer_privacy_device"

    private const val PROVIDER = "AndroidKeyStore"
    private const val KEY_SIZE = 256

    /** 生物识别门控的 KEK；密钥不存在时 [PrivacyAuthKek.isAvailable] 为 false。 */
    fun biometricKek(): PrivacyAuthKek =
        AuthGatedPrivacyKek(BIOMETRIC_ID, keystoreProvider(ALIAS_BIOMETRIC))

    /** 设备密码门控的 KEK（恢复钥匙）；API < 30 时不可用。 */
    fun deviceKek(): PrivacyAuthKek =
        AuthGatedPrivacyKek(DEVICE_ID, keystoreProvider(ALIAS_DEVICE))

    /** 设备密码恢复这条路在当前系统上是否可能（不做**运行时**能力判断，那是 [PrivacyAuthSupport] 的事）。 */
    fun supportsDeviceCredentialKey(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 确保生物识别钥匙存在（首次绑定 / 换指纹导致失效后重建）。
     * 已存在则复用，不重复生成。
     */
    fun ensureBiometricKey() {
        if (containsKey(ALIAS_BIOMETRIC)) return
        val builder = KeyGenParameterSpec.Builder(
            ALIAS_BIOMETRIC,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(KEY_SIZE)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            // 每次使用都要重新认证（不允许「解锁后 N 秒内免认证」）
            .setRandomizedEncryptionRequired(true)
            // 换/新增指纹后旧密钥失效：宁可让用户用 PIN 重绑，也不让新指纹悄悄接管旧数据
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generate(builder.build())
    }

    /**
     * 确保设备密码钥匙存在。**API < 30 直接抛异常** —— 调用方应先问
     * [supportsDeviceCredentialKey]，不要盲目创建。
     */
    fun ensureDeviceKey() {
        if (!supportsDeviceCredentialKey()) {
            throw PrivacyCryptoException("设备密码恢复需要 Android 11 及以上")
        }
        if (containsKey(ALIAS_DEVICE)) return
        generateDeviceKey()
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun generateDeviceKey() {
        val spec = KeyGenParameterSpec.Builder(
            ALIAS_DEVICE,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(KEY_SIZE)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setRandomizedEncryptionRequired(true)
            // 设备密码钥匙与生物录入无关：换指纹不该让它失效
            .setInvalidatedByBiometricEnrollment(false)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .build()
        generate(spec)
    }

    /** 删除指定钥匙（幂等）；解绑恢复钥匙时调用。 */
    fun deleteKey(alias: String) {
        val keyStore = keyStore()
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    fun containsKey(alias: String): Boolean = runCatching {
        keyStore().containsAlias(alias)
    }.getOrDefault(false)

    private fun keystoreProvider(alias: String): PrivacyKeyProvider = object : PrivacyKeyProvider {
        override fun get(): SecretKey = loadKey(alias)
        override fun exists(): Boolean = containsKey(alias)
        override fun delete() = deleteKey(alias)
    }

    private fun loadKey(alias: String): SecretKey {
        val entry = try {
            keyStore().getEntry(alias, null)
        } catch (e: Exception) {
            // 换指纹后 Keystore 会在取用时抛 KeyPermanentlyInvalidatedException
            throw PrivacyCryptoException("认证密钥已失效: $alias", e)
        } as? KeyStore.SecretKeyEntry ?: throw PrivacyCryptoException("认证密钥不存在: $alias")
        return entry.secretKey
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun generate(spec: KeyGenParameterSpec) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(spec)
        generator.generateKey()
    }
}
