package app.myplayer.core.security.privacy

/**
 * 当前设备能提供哪些隐私恢复钥匙。
 *
 * 只描述**能力**，不负责弹窗；输入全部由调用方探好后传入，因此可纯 JVM 单测。
 */
data class PrivacyAuthCapability(
    /** 能否绑定生物识别钥匙（KEK‑BIO）。 */
    val biometric: Boolean,
    /** 能否绑定设备密码钥匙（KEK‑DEVICE，方案 Y 的恢复路径）。 */
    val deviceCredential: Boolean,
) {
    /** 忘 PIN 时的处境，决定首次设置要不要强提示。 */
    val recoveryRisk: PrivacyRecoveryRisk
        get() = when {
            deviceCredential -> PrivacyRecoveryRisk.RECOVERABLE
            biometric -> PrivacyRecoveryRisk.BIOMETRIC_ONLY
            else -> PrivacyRecoveryRisk.NO_RECOVERY_KEY
        }

    /** 重启后（指纹暂不可用）是否仍能靠设备密码救回数据。 */
    val survivesRebootWithoutPin: Boolean get() = deviceCredential
}

enum class PrivacyRecoveryRisk {
    /** 设备密码钥匙可用：忘 PIN 可无损恢复（方案 Y 完整形态）。 */
    RECOVERABLE,

    /**
     * 只有生物识别：指纹可用时能救，但**重启后指纹不可用**，
     * 此时忘 PIN 等于数据锁死 —— 首次设置必须提示「请务必记住 PIN」。
     */
    BIOMETRIC_ONLY,

    /** 既无生物也无设备密码钥匙：忘记 PIN 只能清数据重设，首次设置必须强提示。 */
    NO_RECOVERY_KEY,
}

/**
 * 判定设备能力（`docs/隐私模式方案.md` §P0#5）。
 *
 * - 生物识别需要 `BIOMETRIC_STRONG`（弱等级生物因子不能给 Keystore 密钥授权）；
 * - 设备密码钥匙要求 **API 30+**：API 26–29 的 `BiometricPrompt` 不支持
 *   「DEVICE_CREDENTIAL + CryptoObject」，无法用它解封密钥；
 * - [deviceCredentialAvailable] 应由 `BiometricManager.canAuthenticate(DEVICE_CREDENTIAL)`
 *   得来，**不要用 `KeyguardManager.isDeviceSecure`**：2026-10-28 在三星 SM-G9860
 *   （Android 13，确实设了图案锁）上它返回 false，而设备凭据实际能用 ——
 *   用它判断会把「有后路」误报成「忘记 PIN 数据就没了」，首次设置直接把用户吓退。
 */
object PrivacyAuthSupport {

    fun evaluate(
        sdkInt: Int = android.os.Build.VERSION.SDK_INT,
        deviceCredentialAvailable: Boolean,
        biometricStrongAvailable: Boolean,
    ): PrivacyAuthCapability = PrivacyAuthCapability(
        // API 26 起才有 per-use 认证的 Keystore（minSdk 也是 26），保留判断便于单测覆盖历史版本
        biometric = sdkInt >= MIN_BIOMETRIC_SDK && biometricStrongAvailable,
        deviceCredential = sdkInt >= MIN_DEVICE_CREDENTIAL_SDK && deviceCredentialAvailable,
    )

    /** 与 `AndroidKeystorePrivacyKeys.supportsDeviceCredentialKey()` 同一口径。 */
    fun supportsDeviceCredentialKey(sdkInt: Int = android.os.Build.VERSION.SDK_INT): Boolean =
        sdkInt >= MIN_DEVICE_CREDENTIAL_SDK

    const val MIN_BIOMETRIC_SDK: Int = 26
    const val MIN_DEVICE_CREDENTIAL_SDK: Int = 30
}
