package app.myplayer.feature.privacy

import app.myplayer.core.security.privacy.PrivacyAuthCapability
import app.myplayer.core.security.privacy.PrivacyAuthKek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 认证编排的抽象。
 *
 * 存在这一层的唯一理由是**可测**：[PrivacyBiometricGate] 绑死了 `FragmentActivity` 与
 * `BiometricPrompt`，只能在真机上跑；而「先试哪把钥匙、失败了怎么办」这类决策不该跟着
 * 一起进真机。测试里注入替身即可覆盖全部分支。
 */
interface PrivacyAuthenticator {

    fun capability(): PrivacyAuthCapability

    /** 保险箱里是否已绑定该钥匙（不代表 Keystore 密钥仍有效）。 */
    fun isBound(kekId: String): Boolean

    suspend fun enroll(kek: PrivacyAuthKek): PrivacyAuthOutcome

    suspend fun unlock(kek: PrivacyAuthKek): PrivacyAuthOutcome
}

/** 两把恢复钥匙的来源。测试替身可给出 JVM 密钥。 */
interface PrivacyKeystore {

    fun biometric(): PrivacyAuthKek

    /** 当前系统拿不到设备密码钥匙时返回 null（API < 30）。 */
    fun device(): PrivacyAuthKek?
}

/** 一次访问操作的结果。UI 直接按类型提示，不再解析字符串。 */
sealed interface PrivacyAccessResult {

    data object Success : PrivacyAccessResult

    /** 用户取消，静默返回即可。 */
    data object Cancelled : PrivacyAccessResult

    /** 设备条件不满足（没指纹、没设锁屏、版本太低、钥匙没绑定）。 */
    data class Unavailable(val reason: PrivacyAuthUnavailable) : PrivacyAccessResult

    /** 失败：PIN 错、封装损坏、Keystore 密钥失效。 */
    data class Failed(val message: String) : PrivacyAccessResult
}

/**
 * 隐私模式的三条入口流程（`docs/隐私模式方案.md` §P0#5）。
 *
 * | 场景 | 走法 |
 * |---|---|
 * | 首次设置 | PIN 必填 +（可选）指纹一起封同一份 DEK |
 * | 日常进入 | 优先指纹，弹框里点「改用隐私 PIN」回落 PIN |
 * | 忘 PIN | 优先设备密码钥匙，没有则用指纹；解封后**换新 PIN，数据不丢** |
 *
 * 所有落到 `PrivacySessionManager` 的调用都在 IO 线程执行：PBKDF2 210k 次在低端机上
 * 是几百毫秒，放主线程会卡住认证框消失后的那一帧。
 *
 * 反过来，[PrivacyAuthenticator] 的调用**必须**留在主线程（`BiometricPrompt` 只能在主线程构造）。
 */
class PrivacyAccessCoordinator(
    private val manager: PrivacySessionManager,
    private val authenticator: PrivacyAuthenticator,
    private val keystore: PrivacyKeystore,
) {

    val capability: PrivacyAuthCapability get() = authenticator.capability()

    fun isConfigured(): Boolean = manager.isConfigured()

    /** 当前能不能直接用指纹进（保险箱条目 + 本机能力都满足）。 */
    fun canUseBiometrics(): Boolean =
        capability.biometric && authenticator.isBound(keystore.biometric().id)

    /**
     * 已绑定的恢复钥匙（`docs/隐私模式方案.md` §7.5 的三态检查）。
     *
     * PIN 恒存在（保险箱最少有一把 PIN 封装）；这里再探指纹与设备密码两把。
     * 门口据此显示「本机现在靠什么能进 / 能救」，换指纹导致 KEK-BIO 失效时也一眼看得出来。
     */
    fun boundKeys(): Set<String> = buildSet {
        add("pin")
        runCatching { if (authenticator.isBound(keystore.biometric().id)) add("bio") }
        keystore.device()?.let { device ->
            runCatching { if (authenticator.isBound(device.id)) add("device") }
        }
    }

    /** 忘 PIN 时手上有没有可用的后路。首次设置据此决定提示强度。 */
    fun hasRecoveryKey(): Boolean {
        val device = keystore.device()
        return (device != null && authenticator.isBound(device.id)) ||
            authenticator.isBound(keystore.biometric().id)
    }

    /**
     * 日常进入：走指纹。用户点弹框的「改用隐私 PIN」会得到 [PrivacyAccessResult.Cancelled]，
     * 由 UI 切换到 PIN 输入，不算错误。
     */
    suspend fun enterWithBiometrics(): PrivacyAccessResult {
        if (!capability.biometric) {
            return PrivacyAccessResult.Unavailable(PrivacyAuthUnavailable.NO_HARDWARE)
        }
        val kek = keystore.biometric()
        return when (val outcome = authenticator.unlock(kek)) {
            is PrivacyAuthOutcome.Authorized -> openAuthorized(outcome)
            is PrivacyAuthOutcome.Cancelled -> PrivacyAccessResult.Cancelled
            is PrivacyAuthOutcome.Unavailable -> PrivacyAccessResult.Unavailable(outcome.reason)
            is PrivacyAuthOutcome.Failed -> PrivacyAccessResult.Failed(outcome.message)
        }
    }

    /** PIN 解锁（指纹不可用 / 用户主动选择）。 */
    suspend fun enterWithPin(pin: CharArray): PrivacyAccessResult = withContext(Dispatchers.IO) {
        manager.openWithPin(pin).fold(
            onSuccess = { PrivacyAccessResult.Success },
            onFailure = { PrivacyAccessResult.Failed(it.message ?: "隐私 PIN 不正确") },
        )
    }

    /**
     * 首次设置。
     *
     * [enableBiometrics] 为 true 时会先弹一次认证把指纹钥匙建起来 —— 这一步必须在主线程，
     * 所以**不能**包在 IO 里；只有随后的 PBKDF2 + 落盘才切到 IO。
     *
     * 认证失败**不阻断设置**：回退成纯 PIN 保险箱并标记 [SetupResult.Created.biometricSkipped]，
     * 否则用户会因为传感器不好用而彻底设不了隐私。
     */
    suspend fun setup(pin: CharArray, enableBiometrics: Boolean): SetupResult {
        var biometricSkipped = false
        val authorizedKeys = if (enableBiometrics && capability.biometric) {
            when (val outcome = authenticator.enroll(keystore.biometric())) {
                is PrivacyAuthOutcome.Authorized -> listOf(outcome.key)
                is PrivacyAuthOutcome.Cancelled,
                is PrivacyAuthOutcome.Unavailable,
                is PrivacyAuthOutcome.Failed,
                -> {
                    biometricSkipped = true
                    emptyList()
                }
            }
        } else {
            // 用户想开但设备给不了（没指纹 / 版本低），也算「没开成」
            biometricSkipped = enableBiometrics
            emptyList()
        }

        return withContext(Dispatchers.IO) {
            manager.create(pin, authorizedKeys = authorizedKeys).fold(
                onSuccess = { SetupResult.Created(biometricSkipped = biometricSkipped) },
                onFailure = { SetupResult.Failed(it.message ?: "创建隐私模式失败") },
            )
        }
    }

    /**
     * 忘 PIN 恢复：用设备密码（更可靠，重启后仍可用）或指纹解封 DEK，换新 PIN。
     *
     * DEK 不变，所以隐私数据全部保留 —— 这就是方案 Y 的核心。
     */
    suspend fun recover(newPin: CharArray): PrivacyAccessResult {
        val device = keystore.device()?.takeIf { authenticator.isBound(it.id) }
        val candidate = device ?: keystore.biometric().takeIf { authenticator.isBound(it.id) }
            ?: return PrivacyAccessResult.Unavailable(PrivacyAuthUnavailable.NO_RECOVERY_KEY)

        return when (val outcome = authenticator.unlock(candidate)) {
            is PrivacyAuthOutcome.Authorized -> withContext(Dispatchers.IO) {
                manager.resetPinWithAuthorized(outcome.key, newPin).fold(
                    onSuccess = { PrivacyAccessResult.Success },
                    onFailure = { PrivacyAccessResult.Failed(it.message ?: "重设隐私 PIN 失败") },
                )
            }

            is PrivacyAuthOutcome.Cancelled -> PrivacyAccessResult.Cancelled
            is PrivacyAuthOutcome.Unavailable -> PrivacyAccessResult.Unavailable(outcome.reason)
            is PrivacyAuthOutcome.Failed -> PrivacyAccessResult.Failed(outcome.message)
        }
    }

    /** 已进入隐私模式后补绑/刷新指纹（首次没开、或换指纹后旧钥匙失效）。 */
    suspend fun bindBiometrics(): PrivacyAccessResult {
        if (!capability.biometric) {
            return PrivacyAccessResult.Unavailable(PrivacyAuthUnavailable.NO_HARDWARE)
        }
        return when (val outcome = authenticator.enroll(keystore.biometric())) {
            is PrivacyAuthOutcome.Authorized -> withContext(Dispatchers.IO) {
                manager.bindAuthorizedKey(outcome.key).fold(
                    onSuccess = { PrivacyAccessResult.Success },
                    onFailure = { PrivacyAccessResult.Failed(it.message ?: "绑定指纹失败") },
                )
            }

            is PrivacyAuthOutcome.Cancelled -> PrivacyAccessResult.Cancelled
            is PrivacyAuthOutcome.Unavailable -> PrivacyAccessResult.Unavailable(outcome.reason)
            is PrivacyAuthOutcome.Failed -> PrivacyAccessResult.Failed(outcome.message)
        }
    }

    /** 退出隐私模式（退后台 / 手动）。 */
    fun lock() = manager.lock()

    private suspend fun openAuthorized(outcome: PrivacyAuthOutcome.Authorized): PrivacyAccessResult =
        withContext(Dispatchers.IO) {
            manager.openWithAuthorized(outcome.key).fold(
                onSuccess = { PrivacyAccessResult.Success },
                onFailure = { PrivacyAccessResult.Failed(it.message ?: "隐私验证失败") },
            )
        }
}

/** 首次设置的结果。 */
sealed interface SetupResult {

    /** 建好了。[biometricSkipped] = 用户要开指纹但没成功，保险箱只剩 PIN 一把钥匙。 */
    data class Created(val biometricSkipped: Boolean) : SetupResult

    data class Failed(val message: String) : SetupResult
}
