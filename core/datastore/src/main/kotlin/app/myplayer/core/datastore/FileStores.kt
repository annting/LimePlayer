package app.myplayer.core.datastore

import app.myplayer.core.model.SourceType
import app.myplayer.core.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

/**
 * 服务器配置以**密文**写入 `filesDir/servers.bin`（令牌与配置均需加密存储）。
 * 加载失败（文件损坏、或换机后 Keystore 密钥失效）时退化为空配置，不抛异常、不崩启动。
 */
class FileServerConfigStore(
    private val file: File,
    private val cipher: SecretCipher,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ServerConfigStore {

    private val state = MutableStateFlow(load())

    override fun observeAll(): Flow<List<ServerConfig>> = state.asStateFlow()

    override suspend fun upsert(config: ServerConfig) {
        write(state.value.filterNot { it.id == config.id } + config)
    }

    override suspend fun remove(id: String) {
        write(state.value.filterNot { it.id == id })
    }

    private fun load(): List<ServerConfig> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        json.decodeFromString<List<ServerConfigRecord>>(String(cipher.decrypt(file.readBytes())))
            .map { ServerConfig(it.id, SourceType.valueOf(it.sourceType), it.name, it.baseUrl, it.userId) }
    }.getOrElse { emptyList() }

    private fun write(list: List<ServerConfig>) {
        val records = list.map { ServerConfigRecord(it.id, it.sourceType.name, it.name, it.baseUrl, it.userId) }
        file.parentFile?.mkdirs()
        file.writeBytes(cipher.encrypt(json.encodeToString(records).toByteArray()))
        state.value = list
    }
}
