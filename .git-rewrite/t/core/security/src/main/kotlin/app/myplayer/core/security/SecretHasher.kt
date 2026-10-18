package app.myplayer.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 派生与常量时间比对。
 * 迭代次数为生产默认值（120k）；测试传小值以提速。
 */
class SecretHasher(
    private val iterations: Int = 120_000,
    private val keyLengthBits: Int = 256,
    private val random: SecureRandom = SecureRandom(),
) {
    fun newSalt(size: Int = 16): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun derive(secret: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, keyLengthBits)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun verify(secret: String, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(derive(secret, salt), expected)
}
