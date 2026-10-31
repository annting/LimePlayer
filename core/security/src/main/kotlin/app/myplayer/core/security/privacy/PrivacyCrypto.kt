package app.myplayer.core.security.privacy

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 隐私加密失败（格式非法 / 认证不通过 / 解密失败）。 */
class PrivacyCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 隐私模式密码学原语（纯 JVM，便于单测）。
 *
 * **与普通模式（[app.myplayer.core.security.KeystoreSecretCipher]：AES/GCM）刻意不同**：
 * - 载荷加密：AES/CBC/PKCS5Padding + HMAC-SHA256，严格 **encrypt-then-MAC**（先验 MAC 再解密）。
 * - 密钥派生：HKDF-SHA256 从 DEK 派生**独立**的 encKey / macKey（禁止一钥两用）。
 * - 密文版本化，AAD 绑定用途与对象（如 `privacy-payload:v1:item:<id>`），防密文互换。
 *
 * 密文格式：`version(1B) || ivLen(1B) || iv || ciphertext || mac(32B)`。
 * MAC 输入：`len(aad,4B) || aad || version || ivLen || iv || ciphertext`。
 */
object PrivacyCrypto {
    const val VERSION: Int = 1
    const val KEY_SIZE: Int = 32

    private const val HEADER_SIZE = 2
    private const val IV_SIZE = 16
    private const val MAC_SIZE = 32
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val CIPHER_TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private val HKDF_SALT = "LimePlayer/privacy/hkdf/v1".toByteArray(Charsets.UTF_8)

    fun randomBytes(size: Int, random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(size).also { random.nextBytes(it) }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /**
     * HKDF-SHA256（RFC 5869）：extract + expand。
     * [salt] 为空时按 RFC 使用 32 字节零值（**不等于把口令当盐**）。
     */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * KEY_SIZE)) { "HKDF 输出长度非法: $length" }
        val effectiveSalt = if (salt.isEmpty()) ByteArray(KEY_SIZE) else salt
        val prk = hmac(effectiveSalt, ikm)
        return try {
            val output = ByteArray(length)
            var previous = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < length) {
                val mac = Mac.getInstance(HMAC_ALGORITHM)
                mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous.fill(0)
                previous = mac.doFinal()
                val take = minOf(previous.size, length - offset)
                System.arraycopy(previous, 0, output, offset, take)
                offset += take
                counter++
            }
            previous.fill(0)
            output
        } finally {
            prk.fill(0)
        }
    }

    /**
     * 用 [dek] 加密载荷。[aad] 必须绑定用途与对象，例如：
     * `privacy-payload:v1:server:<serverId>` / `privacy-payload:v1:item:<itemId>`。
     */
    fun encryptPayload(dek: ByteArray, plaintext: ByteArray, aad: String): ByteArray {
        val (encKey, macKey) = deriveKeys(dek)
        try {
            val iv = randomBytes(IV_SIZE)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
            val ciphertext = cipher.doFinal(plaintext)
            val header = byteArrayOf(VERSION.toByte(), IV_SIZE.toByte())
            val mac = hmac(macKey, macInput(aad, header, iv, ciphertext))
            return header + iv + ciphertext + mac
        } finally {
            encKey.fill(0)
            macKey.fill(0)
        }
    }

    /** 解密载荷：先以常数时间验证 MAC，再解密（MAC 失败直接拒绝，防 padding oracle）。 */
    fun decryptPayload(dek: ByteArray, blob: ByteArray, aad: String): ByteArray {
        if (blob.size < HEADER_SIZE + IV_SIZE + MAC_SIZE) {
            throw PrivacyCryptoException("隐私密文格式非法（长度不足）")
        }
        val version = blob[0].toInt() and 0xFF
        if (version != VERSION) {
            throw PrivacyCryptoException("不支持的隐私密文版本: $version")
        }
        val ivSize = blob[1].toInt() and 0xFF
        if (ivSize != IV_SIZE || blob.size < HEADER_SIZE + ivSize + MAC_SIZE) {
            throw PrivacyCryptoException("隐私密文格式非法（IV 长度异常）")
        }

        val header = blob.copyOfRange(0, HEADER_SIZE)
        val iv = blob.copyOfRange(HEADER_SIZE, HEADER_SIZE + ivSize)
        val ciphertext = blob.copyOfRange(HEADER_SIZE + ivSize, blob.size - MAC_SIZE)
        val mac = blob.copyOfRange(blob.size - MAC_SIZE, blob.size)

        val (encKey, macKey) = deriveKeys(dek)
        try {
            val expected = hmac(macKey, macInput(aad, header, iv, ciphertext))
            if (!constantTimeEquals(mac, expected)) {
                throw PrivacyCryptoException("隐私数据认证失败（密文/AAD 被篡改或密钥错误）")
            }
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
            return try {
                cipher.doFinal(ciphertext)
            } catch (e: Exception) {
                throw PrivacyCryptoException("隐私数据解密失败", e)
            }
        } finally {
            encKey.fill(0)
            macKey.fill(0)
        }
    }

    /** 用 KEK 封装 DEK（同 etM 构造，AAD 应绑定 KEK 类型）。 */
    fun encryptKey(kek: ByteArray, dek: ByteArray, aad: String): ByteArray =
        encryptPayload(kek, dek, aad)

    /** 用 KEK 解封 DEK；失败即抛 [PrivacyCryptoException]（可作为 PIN 校验结果）。 */
    fun decryptKey(kek: ByteArray, blob: ByteArray, aad: String): ByteArray =
        decryptPayload(kek, blob, aad)

    /** 从 DEK 经 HKDF 派生独立的 encKey / macKey。 */
    private fun deriveKeys(dek: ByteArray): Pair<ByteArray, ByteArray> {
        require(dek.size == KEY_SIZE) { "DEK 长度必须为 $KEY_SIZE 字节" }
        val material = hkdf(dek, HKDF_SALT, "aes+hmac".toByteArray(Charsets.UTF_8), KEY_SIZE * 2)
        return try {
            material.copyOfRange(0, KEY_SIZE) to material.copyOfRange(KEY_SIZE, KEY_SIZE * 2)
        } finally {
            material.fill(0)
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }

    private fun macInput(aad: String, header: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val aadBytes = aad.toByteArray(Charsets.UTF_8)
        val lengthPrefix = ByteBuffer.allocate(4).putInt(aadBytes.size).array()
        return lengthPrefix + aadBytes + header + iv + ciphertext
    }
}
