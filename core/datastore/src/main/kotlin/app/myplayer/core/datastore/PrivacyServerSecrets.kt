package app.myplayer.core.datastore

import app.myplayer.core.security.privacy.PrivacyCryptoException
import app.myplayer.core.security.privacy.PrivacySecretVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一条私有服务器的**敏感字段**（`docs/隐私模式方案.md` §4.1 / 决策 21）。
 *
 * 为什么不塞进 `ServerConfig`：`servers.bin` 用的是普通模式的 `KeystoreSecretCipher`
 * （AES/GCM、无用户认证、全 App 共用一把），隐私要求「算法/盐/门控都不同」。
 * 所以这里只留 `id / sourceType / name / isPrivate` 的壳在 `servers.bin`，
 * 地址/账号/令牌跟着 DEK 走加密区。
 */
@Serializable
data class ServerSecrets(
    val baseUrl: String = "",
    val userId: String? = null,
    /** 令牌 / SMB 的「账号|密码|域」。普通源仍放 `token_<id>.bin`，私有源放这里。 */
    val token: String? = null,
)

/**
 * 私有源敏感字段的读写门面。
 *
 * **不可用即拒绝**：会话未打开时 [write] 抛 [PrivacyCryptoException]，[read] 返回 null ——
 * 任何「先明文存一下、以后再迁」的退化路径都不存在。
 */
class PrivacyServerSecretStore(
    private val vault: PrivacySecretVault,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    fun isOpen(): Boolean = vault.isOpen()

    /** 是否已有这条服务器的加密条目（不看会话是否打开；用于选令牌存储位置）。 */
    fun has(serverId: String): Boolean = vault.exists(scopeOf(serverId))

    fun read(serverId: String): ServerSecrets? {
        val bytes = vault.read(scopeOf(serverId)) ?: return null
        return runCatching { json.decodeFromString<ServerSecrets>(String(bytes)) }.getOrNull()
    }

    fun write(serverId: String, secrets: ServerSecrets) {
        vault.write(scopeOf(serverId), json.encodeToString(secrets).toByteArray())
    }

    fun delete(serverId: String) {
        if (vault.exists(scopeOf(serverId))) vault.delete(scopeOf(serverId))
    }

    private fun scopeOf(serverId: String): String = "server-$serverId"
}

/**
 * 令牌读写分流：**私有源走加密区（DEK）**，普通源维持原实现（Keystore GCM）。
 *
 * 判定依据是「加密区里有没有这条服务器的条目」—— 因为写令牌时配置已经先落盘
 * （`LoginViewModel` / `AuthGateway` 都是先 upsert 配置再 put 令牌），此刻条目一定存在。
 * 这条顺序不能反：反了的话私有源的令牌会先写进普通 `token_<id>.bin`，等于绕过了加密区。
 */
class PrivacyAwareTokenStore(
    private val delegate: TokenStore,
    private val secrets: PrivacyServerSecretStore,
) : TokenStore {

    override fun put(serverId: String, token: String) {
        if (!secrets.has(serverId)) {
            delegate.put(serverId, token)
            return
        }
        val current = secrets.read(serverId)
            ?: throw PrivacyCryptoException("隐私会话未打开，拒绝写入私有源凭据")
        secrets.write(serverId, current.copy(token = token))
        // 保险：历史版本可能已经把令牌写进了普通区，这里顺手清掉
        runCatching { delegate.remove(serverId) }
    }

    override fun get(serverId: String): String? =
        if (secrets.has(serverId)) secrets.read(serverId)?.token else delegate.get(serverId)

    override fun remove(serverId: String) {
        if (secrets.has(serverId)) {
            val current = secrets.read(serverId)
            if (current == null) {
                // 会话没开 / 条目损坏：至少把加密文件清掉，避免留一条读不出的孤儿
                secrets.delete(serverId)
            } else {
                secrets.write(serverId, current.copy(token = null))
            }
        }
        runCatching { delegate.remove(serverId) }
    }
}
