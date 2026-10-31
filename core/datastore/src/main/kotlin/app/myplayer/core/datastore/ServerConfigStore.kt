package app.myplayer.core.datastore

import app.myplayer.core.model.HidePrivate
import app.myplayer.core.model.PrivacyVisibilityPolicy
import app.myplayer.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

data class ServerConfig(
    val id: String,
    val sourceType: SourceType,
    val name: String,
    val baseUrl: String,
    val userId: String? = null,
    /**
     * **档一 · 配置/源级隐私**（`docs/隐私模式方案.md` §三）。
     *
     * true = 隐私源：普通模式下整条配置**不可见**（切换器/设置/首页枚举全部排除），
     * 因而 `SourceFactory` 构造不出它 —— 也就不会发起任何指向它的网络请求（方案 A）。
     * 进隐私模式后才显出、才可切、才可编辑。
     *
     * 注意：这里只放「是否隐私」这一个布尔；真正敏感的 `baseUrl` / `userId` / 令牌
     * 将来迁入隐私加密区（§4 / 决策 21），本字段只负责「过滤」这一件事。
     */
    val isPrivate: Boolean = false,
)

/**
 * 服务器配置读写。
 *
 * **可见性铁律**：[observeAll] 返回的是**当前可见**的配置 —— 由注入的
 * [PrivacyVisibilityPolicy] 过滤掉 `isPrivate` 的源。普通模式下隐私源连"存在"都看不到，
 * 因此所有消费方（切换器 / 设置页 / 首页 / 当前服务器解析）天然不会碰到它，
 * 不需要各自再写一遍过滤（`docs/隐私模式方案.md` §三 P0#2）。
 *
 * 写入口 [upsert] / [remove] 不受策略影响：隐私模式内才能看到私有源，也才谈得上改它。
 */
interface ServerConfigStore {
    /** 可见配置（普通模式已排除 `isPrivate` 源）。 */
    fun observeAll(): Flow<List<ServerConfig>>
    suspend fun upsert(config: ServerConfig)
    suspend fun remove(id: String)

    /**
     * 把仍留在 `servers.bin` 里的私有源敏感字段迁入加密区（惰性、幂等）。
     *
     * 只有老版本写下的数据需要它；加密区不可写（会话未开）时返回 0，下次进隐私模式再迁。
     * 默认空实现：内存实现与测试替身不涉及明文迁出。
     */
    suspend fun migratePrivateSecrets(): Int = 0
}

class InMemoryServerConfigStore(
    private val privacy: PrivacyVisibilityPolicy = HidePrivate,
) : ServerConfigStore {

    private val state = MutableStateFlow<List<ServerConfig>>(emptyList())

    /** 全量（含隐私源）——只留给写路径与测试断言使用，不对外。 */
    private val all: List<ServerConfig> get() = state.value

    override fun observeAll(): Flow<List<ServerConfig>> =
        combine(state.asStateFlow(), privacy.observeIncludePrivate()) { list, include ->
            if (include) list else list.filterNot { it.isPrivate }
        }

    override suspend fun upsert(config: ServerConfig) {
        state.value = all.filterNot { it.id == config.id } + config
    }

    override suspend fun remove(id: String) {
        state.value = all.filterNot { it.id == id }
    }
}
