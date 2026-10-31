package app.myplayer.core.security.privacy

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 封装 / 解封 DEK 的一把「钥匙」（KEK）。
 *
 * 三种实现：PIN（[PinPrivacyKek]）、生物识别、设备密码（后两者为认证门控 Keystore，见后续）。
 * [id] 会作为隐私保险箱的存储键，必须稳定。
 */
interface PrivacyKek {
    val id: String

    fun wrap(dek: ByteArray): ByteArray

    fun unwrap(blob: ByteArray): ByteArray
}

/**
 * 由隐私 PIN 派生的 KEK。
 *
 * - **盐是随机独立值**：每次 [wrap] 生成 16 字节随机盐，随封装一起存储，但**绝不是 PIN 本身**。
 *   （若 `salt = PIN`，相同 PIN 会产生相同密钥与密文，等同无盐。）
 * - KDF：PBKDF2-HMAC-SHA256，迭代次数随封装存储，便于将来按设备校准调整。
 * - **不存储 PIN 哈希**：PIN 是否正确由「[unwrap] 能否解封 DEK」判定，解封失败即拒绝。
 */
class PinPrivacyKek(
    pin: CharArray,
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) : PrivacyKek {

    /** 私有副本：避免调用方随后清零原数组导致状态不一致。 */
    private val pinChars: CharArray = pin.copyOf()

    init {
        validate(pinChars)
        require(iterations > 0) { "迭代次数必须为正数" }
    }

    override val id: String get() = ID

    override fun wrap(dek: ByteArray): ByteArray {
        val salt = PrivacyCrypto.randomBytes(SALT_SIZE, random)
        val kek = derive(salt, iterations)
        return try {
            val sealed = PrivacyCrypto.encryptKey(kek, dek, aad())
            encode(salt, iterations, sealed)
        } finally {
            kek.fill(0)
        }
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val (salt, storedIterations, sealed) = decode(blob)
        val kek = derive(salt, storedIterations)
        return try {
            PrivacyCrypto.decryptKey(kek, sealed, aad())
        } finally {
            kek.fill(0)
        }
    }

    /** 释放内存中的 PIN 副本。 */
    fun clear() {
        pinChars.fill('\u0000')
    }

    private fun derive(salt: ByteArray, iterationCount: Int): ByteArray {
        val spec = PBEKeySpec(pinChars, salt, iterationCount, KEY_SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun aad(): String = "privacy-dek:v${PrivacyCrypto.VERSION}:$ID"

    /** PIN 封装格式：`version(1) || saltLen(1) || salt || iterations(4) || sealedLen(4) || sealed`。 */
    private fun encode(salt: ByteArray, iterationCount: Int, sealed: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(1 + 1 + salt.size + 4 + 4 + sealed.size)
        buffer.put(FORMAT_VERSION.toByte())
        buffer.put(salt.size.toByte())
        buffer.put(salt)
        buffer.putInt(iterationCount)
        buffer.putInt(sealed.size)
        buffer.put(sealed)
        return buffer.array()
    }

    private fun decode(blob: ByteArray): Triple<ByteArray, Int, ByteArray> {
        if (blob.size < 1 + 1 + 4 + 4) {
            throw PrivacyCryptoException("PIN 封装格式非法（长度不足）")
        }
        val buffer = ByteBuffer.wrap(blob)
        val formatVersion = buffer.get().toInt() and 0xFF
        if (formatVersion != FORMAT_VERSION) {
            throw PrivacyCryptoException("不支持的 PIN 封装版本: $formatVersion")
        }
        val saltSize = buffer.get().toInt() and 0xFF
        if (saltSize !in 8..64 || buffer.remaining() < saltSize + 4 + 4) {
            throw PrivacyCryptoException("PIN 封装格式非法（盐长度异常）")
        }
        val salt = ByteArray(saltSize).also { buffer.get(it) }
        val iterationCount = buffer.getInt()
        if (iterationCount !in 1..MAX_ITERATIONS) {
            throw PrivacyCryptoException("PIN 封装格式非法（迭代次数异常）")
        }
        val sealedSize = buffer.getInt()
        if (sealedSize <= 0 || sealedSize > buffer.remaining()) {
            throw PrivacyCryptoException("PIN 封装格式非法（密文长度异常）")
        }
        val sealed = ByteArray(sealedSize).also { buffer.get(it) }
        return Triple(salt, iterationCount, sealed)
    }

    companion object {
        const val ID = "pin"
        const val SALT_SIZE = 16
        const val DEFAULT_ITERATIONS = 210_000
        const val MIN_LENGTH = 6
        const val MAX_LENGTH = 12

        private const val FORMAT_VERSION = 1
        private const val KEY_SIZE_BITS = 256
        private const val MAX_ITERATIONS = 10_000_000

        /** 隐私 PIN：6–12 位、纯数字。 */
        fun validate(pin: CharArray) {
            require(pin.size in MIN_LENGTH..MAX_LENGTH) {
                "隐私 PIN 需为 $MIN_LENGTH–$MAX_LENGTH 位数字"
            }
            require(pin.all { it.isDigit() }) { "隐私 PIN 只能包含数字" }
        }
    }
}
