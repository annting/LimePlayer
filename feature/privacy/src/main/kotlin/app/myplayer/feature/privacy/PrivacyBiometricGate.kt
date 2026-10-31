package app.myplayer.feature.privacy

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.myplayer.core.security.privacy.AndroidKeystorePrivacyKeys
import app.myplayer.core.security.privacy.AuthorizedPrivacyKey
import app.myplayer.core.security.privacy.PrivacyAuthCapability
import app.myplayer.core.security.privacy.PrivacyAuthKek
import app.myplayer.core.security.privacy.PrivacyAuthSupport
import app.myplayer.core.security.privacy.PrivacyCryptoException
import app.myplayer.core.security.privacy.PrivacyUnlocker
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import kotlin.coroutines.resume

/** 认证编排的结果。区分「用户取消」「设备不支持」「真失败」，UI 提示完全不同。 */
sealed interface PrivacyAuthOutcome {

    /**
     * 认证通过，拿到**已授权**的 [AuthorizedPrivacyKey]。
     *
     * 一个实例只能用一次：Keystore 的按次认证在 `doFinal` 之后就作废了，
     * 重试必须重新走 [PrivacyBiometricGate.unlock] / [PrivacyBiometricGate.enroll]。
     */
    data class Authorized(val key: AuthorizedPrivacyKey) : PrivacyAuthOutcome

    /** 用户主动取消（点取消 / 切后台 / 按返回）。不是错误，不该弹红字。 */
    data object Cancelled : PrivacyAuthOutcome

    /** 设备给不了这把钥匙：没有硬件、没录指纹、未设锁屏、系统版本不够。 */
    data class Unavailable(val reason: PrivacyAuthUnavailable) : PrivacyAuthOutcome

    /** 真失败：Keystore 密钥失效、封装损坏、认证服务异常。需要提示原因。 */
    data class Failed(val message: String) : PrivacyAuthOutcome
}

enum class PrivacyAuthUnavailable(val message: String) {
    /** 设备没有可用的生物识别硬件。 */
    NO_HARDWARE("本机没有可用的生物识别硬件"),

    /** 硬件存在但当前不可用（例如传感器被占用）。 */
    HW_UNAVAILABLE("生物识别暂时不可用，请稍后再试"),

    /** 硬件可用但没录入指纹/面容。 */
    NONE_ENROLLED("请先在系统设置里录入指纹"),

    /** 需要先安装系统安全更新才能用。 */
    SECURITY_UPDATE_REQUIRED("需要先安装系统安全更新"),

    /** 保险箱里没有这把钥匙的条目 —— 从没绑定过。 */
    NOT_BOUND("尚未绑定这把恢复钥匙"),

    /** 设备没设锁屏密码/PIN，无法用设备凭据做恢复。 */
    NO_DEVICE_CREDENTIAL("请先在系统设置里设置锁屏密码"),

    /** 忘 PIN 时一把后路钥匙都没有：数据已经救不回来，只能清数据重设。 */
    NO_RECOVERY_KEY("没有可用的恢复钥匙，忘记 PIN 将无法找回隐私数据"),

    /** 设备密码钥匙需要 Android 11+（低版本 `BiometricPrompt` 不支持 CryptoObject + 设备凭据）。 */
    UNSUPPORTED_SDK("设备密码恢复需要 Android 11 及以上"),
}

/**
 * 生物识别 / 设备密码的认证编排（`docs/隐私模式方案.md` §P0#5）。
 *
 * 把「两阶段封装」的两次 `BiometricPrompt` 调用收敛到一处，供 UI 层直接 `await`：
 *
 * ```
 * 绑定（首次设置 / 补绑指纹）：
 *   ensureBiometricKey() → prepareWrap() → 弹框 → wrapWith(已授权 cipher)
 * 解锁 / 忘 PIN 恢复：
 *   prepareUnwrap(sealed) → 弹框 → unwrapWith(已授权 cipher)
 * ```
 *
 * 两条硬约束：
 * 1. **`BiometricPrompt` 只能由 `FragmentActivity` 构造**（它内部要挂一个 Fragment），
 *    所以 `MainActivity` 的基类是 `FragmentActivity` 而不是 `ComponentActivity`。
 * 2. **钥匙类型决定允许的认证方式**：KEK‑BIO 只接受 `BIOMETRIC_STRONG`，
 *    KEK‑DEVICE 只接受 `DEVICE_CREDENTIAL`。二者混用会导致用户在弹框里选了另一种方式、
 *    但底层密钥只认其中一种，`doFinal` 抛 `UserNotAuthenticatedException` ——
 *    表现为「验证通过了却解不开」，是最难查的一类故障。
 */
class PrivacyBiometricGate(
    private val activity: FragmentActivity,
    private val unlocker: PrivacyUnlocker,
) : PrivacyAuthenticator {

    /** 正在进行的认证框；只用于取消与防 GC（`BiometricPrompt` 自己不保证强引用）。 */
    private var activePrompt: BiometricPrompt? = null

    /** 本机能力：决定首次设置时要不要强提示「忘记 PIN 会丢数据」。 */
    override fun capability(): PrivacyAuthCapability {
        val biometrics = BiometricManager.from(activity)
        val strong = biometrics.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        // 设备凭据一律问 BiometricManager，**不碰 KeyguardManager.isDeviceSecure** ——
        // 后者在三星 SM-G9860（Android 13，有图案锁）上返回 false（2026-10-28 实测），
        // 而 canAuthenticate 才是 BiometricPrompt 实际会用的判定，两者不一致时以后者为准。
        val deviceResult = if (PrivacyAuthSupport.supportsDeviceCredentialKey()) {
            biometrics.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            UNSUPPORTED
        }
        val capability = PrivacyAuthSupport.evaluate(
            sdkInt = Build.VERSION.SDK_INT,
            deviceCredentialAvailable = deviceResult == BiometricManager.BIOMETRIC_SUCCESS,
            biometricStrongAvailable = strong == BiometricManager.BIOMETRIC_SUCCESS,
        )
        // 一行诊断：能力判定直接决定首次设置的提示强度（「忘记 PIN 数据会丢」），
        // 判错了会吓到用户或让人误以为有后路，出问题时需要能一眼看出各个输入值。
        android.util.Log.i(
            "PrivacyCapability",
            "sdk=${Build.VERSION.SDK_INT} strong=$strong deviceCredential=$deviceResult -> $capability",
        )
        return capability
    }

    /** 绑定用：确保 Keystore 里已有对应钥匙（已存在则复用），然后弹框拿授权。 */
    override suspend fun enroll(kek: PrivacyAuthKek): PrivacyAuthOutcome {
        val cipher = try {
            ensureKey(kek)
            kek.prepareWrap()
        } catch (e: KeySetupException) {
            return PrivacyAuthOutcome.Unavailable(e.reason)
        } catch (e: Exception) {
            return PrivacyAuthOutcome.Failed(e.message ?: "无法准备认证密钥")
        }
        return prompt(kek, cipher, enrolling = true)
    }

    /** 解锁 / 忘 PIN 恢复用：按保险箱里的封装准备好待授权 Cipher，再弹框。 */
    override suspend fun unlock(kek: PrivacyAuthKek): PrivacyAuthOutcome {
        // Keystore 密钥可能因换指纹而失效，但保险箱条目还在 —— 两者要分开判断
        if (!isBound(kek.id)) return PrivacyAuthOutcome.Unavailable(PrivacyAuthUnavailable.NOT_BOUND)

        val cipher = try {
            unlocker.prepareUnwrapFor(kek)
        } catch (e: PrivacyCryptoException) {
            return PrivacyAuthOutcome.Failed(e.message ?: "隐私保险箱数据损坏")
        } catch (e: Exception) {
            return PrivacyAuthOutcome.Failed(e.message ?: "无法准备认证密钥")
        } ?: return PrivacyAuthOutcome.Unavailable(PrivacyAuthUnavailable.NOT_BOUND)

        return prompt(kek, cipher, enrolling = false)
    }

    /** 保险箱里已有这一把钥匙（不代表 Keystore 密钥还有效 —— 换指纹会失效）。 */
    override fun isBound(kekId: String): Boolean =
        runCatching { unlocker.hasKek(kekId) }.getOrDefault(false)

    /** 取消进行中的认证（退后台时调用，避免弹框挂在已不可见的界面上）。 */
    fun cancel() {
        activePrompt?.cancelAuthentication()
        activePrompt = null
    }

    private fun ensureKey(kek: PrivacyAuthKek) {
        when (kek.id) {
            AndroidKeystorePrivacyKeys.BIOMETRIC_ID -> {
                val capability = capability()
                when {
                    !capability.biometric && !hasBiometricHardware() ->
                        throw KeySetupException(PrivacyAuthUnavailable.NO_HARDWARE)

                    !capability.biometric ->
                        throw KeySetupException(PrivacyAuthUnavailable.NONE_ENROLLED)
                }
                AndroidKeystorePrivacyKeys.ensureBiometricKey()
            }

            AndroidKeystorePrivacyKeys.DEVICE_ID -> {
                if (!PrivacyAuthSupport.supportsDeviceCredentialKey()) {
                    throw KeySetupException(PrivacyAuthUnavailable.UNSUPPORTED_SDK)
                }
                if (!capability().deviceCredential) {
                    throw KeySetupException(PrivacyAuthUnavailable.NO_DEVICE_CREDENTIAL)
                }
                AndroidKeystorePrivacyKeys.ensureDeviceKey()
            }

            else -> throw PrivacyCryptoException("未知的恢复钥匙: ${kek.id}")
        }
    }

    /** 区分「没硬件」和「没录指纹」：两者的引导路径不同（一个无解，一个去设置里录）。 */
    private fun hasBiometricHardware(): Boolean {
        val biometrics = BiometricManager.from(activity)
        val weak = biometrics.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        // 有硬件但弱等级都不支持 ⇒ 没有传感器；支持弱但强不可用 ⇒ 有传感器但没录或版本低
        return weak != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE &&
            weak != BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
    }

    private suspend fun prompt(
        kek: PrivacyAuthKek,
        cipher: Cipher,
        enrolling: Boolean,
    ): PrivacyAuthOutcome = suspendCancellableCoroutine { continuation ->
        // 回调可能被系统调用多次（单次失败重试、成功后又来一个 error），只认第一次结论
        val settled = AtomicBoolean(false)

        fun settle(outcome: PrivacyAuthOutcome) {
            if (settled.compareAndSet(false, true)) {
                activePrompt = null
                if (continuation.isActive) continuation.resume(outcome)
            }
        }

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authorized = result.cryptoObject?.cipher
                if (authorized == null) {
                    settle(PrivacyAuthOutcome.Failed("系统未返回已授权的密钥"))
                } else {
                    settle(PrivacyAuthOutcome.Authorized(AuthorizedPrivacyKey(kek, authorized)))
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                settle(
                    if (isCancellation(errorCode)) {
                        PrivacyAuthOutcome.Cancelled
                    } else {
                        val reason = unavailableFor(errorCode)
                        if (reason != null) {
                            PrivacyAuthOutcome.Unavailable(reason)
                        } else {
                            PrivacyAuthOutcome.Failed(errString.toString())
                        }
                    },
                )
            }

            // 单次指纹不匹配：系统自己留在弹框里让用户重试，这里绝不能结束协程
            override fun onAuthenticationFailed() = Unit
        }

        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        activePrompt = prompt

        continuation.invokeOnCancellation {
            settled.set(true)
            prompt.cancelAuthentication()
            activePrompt = null
        }

        try {
            prompt.authenticate(promptInfo(kek, enrolling), BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            // 典型：设备不支持 CryptoObject 认证、或已有弹框正在显示
            settle(PrivacyAuthOutcome.Failed(e.message ?: "无法启动身份验证"))
        }
    }

    private fun promptInfo(kek: PrivacyAuthKek, enrolling: Boolean): BiometricPrompt.PromptInfo {
        val deviceKey = kek.id == AndroidKeystorePrivacyKeys.DEVICE_ID
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (enrolling) "开启指纹快速进入" else "进入隐私模式")
            .setSubtitle(
                if (enrolling) "验证指纹，之后可用它直接进入隐私模式" else "验证以查看隐私内容",
            )
            .setAllowedAuthenticators(
                if (deviceKey) {
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                } else {
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                },
            )
            .setConfirmationRequired(false)

        // 允许 DEVICE_CREDENTIAL 时不能设取消按钮（系统自带），设了会抛 IllegalArgumentException
        if (!deviceKey) {
            builder.setNegativeButtonText("改用隐私 PIN")
        }
        return builder.build()
    }

    private fun isCancellation(errorCode: Int): Boolean = when (errorCode) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
        -> true

        else -> false
    }

    /** 把系统错误码翻成本机能力问题（「设备不支持」比原始英文更有用）。 */
    private fun unavailableFor(errorCode: Int): PrivacyAuthUnavailable? = when (errorCode) {
        BiometricPrompt.ERROR_HW_NOT_PRESENT -> PrivacyAuthUnavailable.NO_HARDWARE
        BiometricPrompt.ERROR_HW_UNAVAILABLE -> PrivacyAuthUnavailable.HW_UNAVAILABLE
        BiometricPrompt.ERROR_NO_BIOMETRICS -> PrivacyAuthUnavailable.NONE_ENROLLED
        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED -> PrivacyAuthUnavailable.SECURITY_UPDATE_REQUIRED
        else -> null
    }

    private class KeySetupException(val reason: PrivacyAuthUnavailable) :
        Exception(reason.message)

    private companion object {
        /** 系统版本够不上设备密码钥匙时用的哨兵值（不占用 `BiometricManager` 的错误码空间）。 */
        const val UNSUPPORTED = -1
    }
}

/**
 * 生产环境的钥匙来源：`AndroidKeystorePrivacyKeys` 里那两把认证门控密钥。
 *
 * 设备密码钥匙在 API < 30 上直接返回 null —— 调用方据此判断「这条恢复路径根本不存在」，
 * 而不是等到弹框时才发现 `BiometricPrompt` 不支持 CryptoObject + 设备凭据。
 */
object AndroidPrivacyKeystore : PrivacyKeystore {

    override fun biometric(): PrivacyAuthKek = AndroidKeystorePrivacyKeys.biometricKek()

    override fun device(): PrivacyAuthKek? =
        if (PrivacyAuthSupport.supportsDeviceCredentialKey()) {
            AndroidKeystorePrivacyKeys.deviceKek()
        } else {
            null
        }
}
