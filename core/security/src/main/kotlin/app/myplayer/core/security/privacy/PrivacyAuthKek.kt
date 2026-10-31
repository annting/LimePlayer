package app.myplayer.core.security.privacy

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 取「加解 DEK 的 AES-256 密钥」的抽象。
 *
 * 生产实现是 AndroidKeyStore 里带用户认证门控的密钥（[AndroidKeystorePrivacyKeys]），
 * 单测注入普通 [javax.crypto.spec.SecretKeySpec] —— 这样 GCM 封装格式、IV 处理、
 * 篡改拒绝等逻辑都能在纯 JVM 上覆盖，只有「授权」这一步依赖真机。
 */
interface PrivacyKeyProvider {
    /** 取密钥；不存在时抛 [PrivacyCryptoException]。 */
    fun get(): SecretKey

    /** 密钥是否存在于 Keystore（不代表当前已通过认证）。 */
    fun exists(): Boolean = true

    /** 删除密钥（解绑恢复钥匙时调用）。 */
    fun delete() = Unit
}

/**
 * 由系统认证（生物识别 / 设备密码）门控的 KEK。
 *
 * **为什么不是同步的 [PrivacyKek]**：AndroidKeyStore 里 `setUserAuthenticationRequired(true)` 的
 * 密钥*只能*被一个经 `BiometricPrompt` 授权的 `Cipher` 使用，授权前 `doFinal` 会抛
 * `UserNotAuthenticatedException`。所以封装/解封必须拆成两阶段：
 *
 * ```
 * cipher = kek.prepareWrap()                 // 授权前：init 出 Cipher
 * biometricPrompt.authenticate(info, CryptoObject(cipher))
 * // 认证成功回调里拿到已授权的 cipher
 * blob = kek.wrapWith(cipher, dek)
 * ```
 *
 * [Cipher] 由调用方在认证成功回调中从 `AuthenticationResult.cryptoObject` 取回，
 * 保证用的是系统刚授权的那一个实例。
 */
interface PrivacyAuthKek {
    /** 保险箱里的稳定标识（`bio` / `device`）。 */
    val id: String

    /** Keystore 里是否已有这把钥匙。 */
    fun isAvailable(): Boolean

    /** 授权前：准备好封装用的 Cipher（ENCRYPT_MODE，随机 IV 已生成）。 */
    fun prepareWrap(): Cipher

    /** 认证成功后：用已授权的 [cipher] 封装 DEK。 */
    fun wrapWith(cipher: Cipher, dek: ByteArray): ByteArray

    /** 授权前：按 [blob] 里的 IV 准备好解封用的 Cipher（DECRYPT_MODE）。 */
    fun prepareUnwrap(blob: ByteArray): Cipher

    /** 认证成功后：用已授权的 [cipher] 解封 DEK。失败即代表密钥/数据不对。 */
    fun unwrapWith(cipher: Cipher, blob: ByteArray): ByteArray

    /** 删除 Keystore 里的密钥（幂等）。 */
    fun deleteKey()
}

/**
 * [PrivacyAuthKek] 的通用实现：直接以 AES/GCM 加解 DEK。
 *
 * 这里用 GCM 而非载荷那套 CBC+HMAC，是因为 **AndroidKeyStore 认证门控密钥只支持 GCM**
 * （`setUserAuthenticationRequired(true)` 时不允许自行提供 IV 的 CBC）。差异体现在：
 * - 载荷（真正的业务数据）仍是 CBC+HMAC + PBKDF2 随机盐，与普通模式的 GCM 不同；
 * - 本类只负责「封装 32 字节 DEK」这一层，密钥在硬件里、按次认证，普通模式没有任何对应物。
 *
 * 封装格式：`version(1) || ivLen(1) || iv || ciphertext(含 16B GCM tag)`。
 * AAD 绑定 KEK 标识，防止把 `bio` 的封装挪到 `device` 条目上使用。
 */
class AuthGatedPrivacyKek(
    override val id: String,
    private val keyProvider: PrivacyKeyProvider,
) : PrivacyAuthKek {

    init {
        require(id.isNotBlank()) { "恢复钥匙标识不能为空" }
    }

    override fun isAvailable(): Boolean = runCatching { keyProvider.exists() }.getOrDefault(false)

    override fun prepareWrap(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher
    }

    override fun wrapWith(cipher: Cipher, dek: ByteArray): ByteArray {
        require(dek.size == PrivacyCrypto.KEY_SIZE) { "DEK 长度必须为 ${PrivacyCrypto.KEY_SIZE} 字节" }
        val iv = cipher.iv ?: throw PrivacyCryptoException("认证密钥未生成 IV")
        if (iv.size !in MIN_IV_SIZE..MAX_IV_SIZE) {
            throw PrivacyCryptoException("认证密钥 IV 长度异常: ${iv.size}")
        }
        val ciphertext = try {
            // AAD 在授权之后再设：认证门控的 Keystore 操作在未授权时行为因版本而异，
            // 放到 doFinal 之前一起做最稳（顺序无关，但必须在 doFinal 前）。
            cipher.updateAAD(aad().toByteArray(Charsets.UTF_8))
            cipher.doFinal(dek)
        } catch (e: Exception) {
            throw PrivacyCryptoException("封装 DEK 失败（认证可能已失效）", e)
        }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + iv.size + ciphertext.size)
        buffer.put(FORMAT_VERSION.toByte())
        buffer.put(iv.size.toByte())
        buffer.put(iv)
        buffer.put(ciphertext)
        return buffer.array()
    }

    override fun prepareUnwrap(blob: ByteArray): Cipher {
        val (iv, _) = parse(blob)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher
    }

    override fun unwrapWith(cipher: Cipher, blob: ByteArray): ByteArray {
        val (_, ciphertext) = parse(blob)
        return try {
            cipher.updateAAD(aad().toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw PrivacyCryptoException("解封 DEK 失败（密钥/数据不匹配，或认证已过期）", e)
        }
    }

    override fun deleteKey() {
        keyProvider.delete()
    }

    private fun key(): SecretKey = try {
        keyProvider.get()
    } catch (e: PrivacyCryptoException) {
        throw e
    } catch (e: Exception) {
        throw PrivacyCryptoException("读取认证密钥失败: $id", e)
    }

    private fun aad(): String = "privacy-dek-auth:v${PrivacyCrypto.VERSION}:$id"

    /** 只做结构解析，不碰密钥 —— 格式非法时在「弹认证框之前」就失败，不打扰用户。 */
    private fun parse(blob: ByteArray): Pair<ByteArray, ByteArray> {
        if (blob.size < HEADER_SIZE + MIN_IV_SIZE + MIN_CIPHERTEXT_SIZE) {
            throw PrivacyCryptoException("认证封装格式非法（长度不足）")
        }
        val version = blob[0].toInt() and 0xFF
        if (version != FORMAT_VERSION) {
            throw PrivacyCryptoException("不支持的认证封装版本: $version")
        }
        val ivSize = blob[1].toInt() and 0xFF
        if (ivSize !in MIN_IV_SIZE..MAX_IV_SIZE) {
            throw PrivacyCryptoException("认证封装格式非法（IV 长度异常: $ivSize）")
        }
        if (blob.size < HEADER_SIZE + ivSize + MIN_CIPHERTEXT_SIZE) {
            throw PrivacyCryptoException("认证封装格式非法（密文截断）")
        }
        return blob.copyOfRange(HEADER_SIZE, HEADER_SIZE + ivSize) to
            blob.copyOfRange(HEADER_SIZE + ivSize, blob.size)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = 1
        private const val HEADER_SIZE = 2
        private const val TAG_BITS = 128
        /** GCM 最小 16B tag，所以密文至少这么长（DEK 本身 32B，这里只是格式下限）。 */
        private const val MIN_CIPHERTEXT_SIZE = TAG_BITS / 8
        private const val MIN_IV_SIZE = 12
        private const val MAX_IV_SIZE = 16
    }
}
