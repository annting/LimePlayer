package app.myplayer.core.security

import java.util.Base64

/** 对称加密抽象：生产实现是 [KeystoreSecretCipher]，测试用内存实现。 */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(cipher: ByteArray): ByteArray
}

/**
 * 凭据记录（**不以明文保存图案/PIN**，只存加盐派生的 Base64 哈希）。
 * 该记录由 [CredentialStorage] 实现负责加密后落盘。
 */
data class CredentialRecord(
    val patternHash: String,   // Base64(派生结果)
    val patternSalt: String,   // Base64
    val backupPinHash: String? = null,
    val backupPinSalt: String? = null,
    val failedAttempts: Int = 0,
    val lastFailedAtMillis: Long = 0L,
)

interface CredentialStorage {
    fun read(): CredentialRecord?
    fun write(record: CredentialRecord)
    fun clear()
}

class InMemoryCredentialStorage : CredentialStorage {
    private var record: CredentialRecord? = null
    override fun read() = record
    override fun write(record: CredentialRecord) { this.record = record }
    override fun clear() { record = null }
}

internal fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
internal fun String.fromBase64(): ByteArray = Base64.getDecoder().decode(this)
