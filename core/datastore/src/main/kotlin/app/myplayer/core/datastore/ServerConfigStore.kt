package app.myplayer.core.datastore

import app.myplayer.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

data class ServerConfig(
    val id: String,
    val sourceType: SourceType,
    val name: String,
    val baseUrl: String,
    val userId: String? = null,
)

interface ServerConfigStore {
    fun observeAll(): Flow<List<ServerConfig>>
    suspend fun upsert(config: ServerConfig)
    suspend fun remove(id: String)
}

class InMemoryServerConfigStore : ServerConfigStore {
    private val state = MutableStateFlow<List<ServerConfig>>(emptyList())
    override fun observeAll(): Flow<List<ServerConfig>> = state
    override suspend fun upsert(config: ServerConfig) {
        state.value = state.value.filterNot { it.id == config.id } + config
    }
    override suspend fun remove(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }
}
