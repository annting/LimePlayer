package app.myplayer.di

import app.myplayer.core.security.CredentialRecord
import app.myplayer.core.security.CredentialStorage
import app.myplayer.core.security.SecretCipher
import java.io.File

/**
 * 应用锁凭据的落盘实现：经 [SecretCipher]（Android Keystore）加密后写入文件。
 * 记录用 `|` 连接（各字段本身不含 `|`：Base64 与数字字符串）。
 */
class FileCredentialStorage(private val file: File, private val cipher: SecretCipher) : CredentialStorage {

    override fun read(): CredentialRecord? =
        runCatching { file.takeIf { it.exists() }?.let { decode(String(cipher.decrypt(it.readBytes()))) } }.getOrNull()

    override fun write(record: CredentialRecord) {
        file.parentFile?.mkdirs()
        file.writeBytes(cipher.encrypt(encode(record).toByteArray()))
    }

    override fun clear() { if (file.exists()) file.delete() }

    private fun encode(r: CredentialRecord) = listOf(
        r.patternHash, r.patternSalt,
        r.backupPinHash ?: "", r.backupPinSalt ?: "",
        r.failedAttempts.toString(), r.lastFailedAtMillis.toString(),
    ).joinToString("|")

    private fun decode(s: String): CredentialRecord {
        val p = s.split("|")
        return CredentialRecord(
            patternHash = p[0], patternSalt = p[1],
            backupPinHash = p[2].ifEmpty { null }, backupPinSalt = p[3].ifEmpty { null },
            failedAttempts = p[4].toInt(), lastFailedAtMillis = p[5].toLong(),
        )
    }
}
