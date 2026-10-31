package app.myplayer.core.datastore

import app.myplayer.core.model.HidePrivate
import app.myplayer.core.model.PrivacyVisibilityPolicy
import app.myplayer.core.model.SourceType
import app.myplayer.core.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** filesDir 下的字节文件读写，供 [EncryptedTokenStore] 使用。 */
class DirByteFileStorage(private val dir: File) {
    fun read(name: String): ByteArray? = File(dir, name).takeIf { it.exists() }?.readBytes()
    fun write(name: String, bytes: ByteArray) {
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
    }
    fun delete(name: String) { File(dir, name).delete() }
}

@Serializable
private data class ServerConfigRecord(
    val id: String,
    val sourceType: String,
    val name: String,
    val baseUrl: String,
    val userId: String? = null,
    /**
     * 档一隐私标记。带默认值 + `ignoreUnknownKeys = true`：
     * 老版本写下的 `servers.bin`（没有这个键）读出来就是 `false`，**无需迁移**。
     */
    val isPrivate: Boolean = false,
)

/**
 * 服务器配置以**密文**写入 `filesDir/servers.bin`（令牌与配置均需加密存储）。
 * 加载失败（文件损坏、或换机后 Keystore 密钥失效）时退化为空配置，不抛异常、不崩启动。
 *
 * `privacy` 只作用于**读**（[observeAll]）：普通模式下隐私源不出现在任何列表里，
 * 写路径仍以内存中的全量列表为准，避免「看不见就把隐私源顺手删掉」。
 *
 * **私有源的敏感字段不入本文件**（`docs/隐私模式方案.md` §4.1 / 决策 21）：
 * `servers.bin` 里只留 `id / sourceType / name / isPrivate` 的壳，
 * `baseUrl` / `userId` 写进 [PrivacyServerSecretStore]（DEK 加密区）。
 * 读取时只在**隐私模式下**水合（[observeAll] 的 includePrivate 分支），
 * 因此普通模式连私有源的地址都拿不到 —— 也就无从发起网络请求（方案 A）。
 */
class FileServerConfigStore(
    private val file: File,
    private val cipher: SecretCipher,
    private val privacy: PrivacyVisibilityPolicy = HidePrivate,
    /** 私有源敏感字段的加密区；null = 不启用（纯内存/测试装配）。 */
    private val secrets: PrivacyServerSecretStore? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ServerConfigStore {

    private val state = MutableStateFlow(load())

    override fun observeAll(): Flow<List<ServerConfig>> =
        combine(state.asStateFlow(), privacy.observeIncludePrivate()) { list, include ->
            if (!include) {
                list.filterNot { it.isPrivate }
            } else {
                // 隐私模式才水合：地址/账号从加密区读，读不到就是空壳（会话已关时不会走到这里）
                list.map(::hydrate)
            }
        }

    override suspend fun upsert(config: ServerConfig) {
        val stored = if (config.isPrivate && secrets != null) shellOf(config) else config
        write(state.value.filterNot { it.id == stored.id } + stored)
    }

    override suspend fun remove(id: String) {
        write(state.value.filterNot { it.id == id })
        secrets?.delete(id)
    }

    /**
     * 旧版本把私有源的地址也写在了 `servers.bin` 里；本方法把它们迁进加密区并重写壳。
     *
     * 幂等；会话未打开（加密区不可写）时直接返回 0，等下次进隐私模式再迁。
     * 迁移失败不清原字段 —— 宁可在文件里多留一份，也不能把用户的配置弄丢。
     */
    override suspend fun migratePrivateSecrets(): Int {
        val store = secrets ?: return 0
        if (!store.isOpen()) return 0
        val legacy = state.value.filter { it.isPrivate && it.baseUrl.isNotBlank() }
        if (legacy.isEmpty()) return 0

        var migrated = 0
        val updated = state.value.map { config ->
            if (!config.isPrivate || config.baseUrl.isBlank()) {
                config
            } else {
                runCatching {
                    store.write(
                        config.id,
                        ServerSecrets(
                            baseUrl = config.baseUrl,
                            userId = config.userId,
                            token = store.read(config.id)?.token,
                        ),
                    )
                    migrated++
                    config.copy(baseUrl = "", userId = null)
                }.getOrDefault(config)
            }
        }
        if (migrated > 0) write(updated)
        return migrated
    }

    /** `baseUrl / userId` 落加密区，返回只含非敏感字段的壳。加密区不可写时抛错（绝不退化明文）。 */
    private fun shellOf(config: ServerConfig): ServerConfig {
        val store = secrets ?: return config
        val existing = store.read(config.id)
        store.write(
            config.id,
            ServerSecrets(
                // 空串 = 这是一份「只改名字」的壳（调用方拿到的原配置已被水合，正常不会为空）
                baseUrl = config.baseUrl.ifBlank { existing?.baseUrl.orEmpty() },
                userId = config.userId ?: existing?.userId,
                token = existing?.token,
            ),
        )
        return config.copy(baseUrl = "", userId = null)
    }

    /** 壳 → 完整配置。会话未开/没有加密条目时原样返回（此时调用方本就不该看到它）。 */
    private fun hydrate(config: ServerConfig): ServerConfig {
        if (!config.isPrivate) return config
        val secret = secrets?.read(config.id) ?: return config
        return config.copy(
            baseUrl = secret.baseUrl.ifBlank { config.baseUrl },
            userId = secret.userId ?: config.userId,
        )
    }

    private fun load(): List<ServerConfig> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        json.decodeFromString<List<ServerConfigRecord>>(String(cipher.decrypt(file.readBytes())))
            .map {
                ServerConfig(
                    id = it.id,
                    sourceType = SourceType.valueOf(it.sourceType),
                    name = it.name,
                    baseUrl = it.baseUrl,
                    userId = it.userId,
                    isPrivate = it.isPrivate,
                )
            }
    }.getOrElse { emptyList() }

    private fun write(list: List<ServerConfig>) {
        val records = list.map {
            ServerConfigRecord(
                id = it.id,
                sourceType = it.sourceType.name,
                name = it.name,
                // 私有源的地址/账号永不进本文件：即便调用方传来的是水合后的完整配置，
                // 落盘前也要削成壳（双保险，防止某处忘了先走 shellOf）
                baseUrl = if (it.isPrivate) "" else it.baseUrl,
                userId = if (it.isPrivate) null else it.userId,
                isPrivate = it.isPrivate,
            )
        }
        file.parentFile?.mkdirs()
        file.writeBytes(cipher.encrypt(json.encodeToString(records).toByteArray()))
        state.value = list
    }
}
