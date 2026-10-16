package app.myplayer.core.datastore

import app.myplayer.core.security.SecretCipher
import java.nio.charset.Charset

interface TokenStore {
    fun put(serverId: String, token: String)
    fun get(serverId: String): String?
    fun remove(serverId: String)
}

class InMemoryTokenStore : TokenStore {
    private val map = mutableMapOf<String, String>()
    override fun put(serverId: String, token: String) { map[serverId] = token }
    override fun get(serverId: String): String? = map[serverId]
    override fun remove(serverId: String) { map.remove(serverId) }
}

/** 令牌以密文写入磁盘；读写通过 [SecretCipher]，明文不落盘（M1 DoD 要求 adb 检查为密文）。 */
class EncryptedTokenStore(
    private val cipher: SecretCipher,
    private val readBytes: (String) -> ByteArray?,
    private val writeBytes: (String, ByteArray) -> Unit,
    private val deleteFile: (String) -> Unit,
    private val charset: Charset = Charsets.UTF_8,
) : TokenStore {
    private fun fileName(serverId: String) = "token_$serverId.bin"

    override fun put(serverId: String, token: String) =
        writeBytes(fileName(serverId), cipher.encrypt(token.toByteArray(charset)))

    override fun get(serverId: String): String? =
        readBytes(fileName(serverId))?.let { String(cipher.decrypt(it), charset) }

    override fun remove(serverId: String) = deleteFile(fileName(serverId))
}
