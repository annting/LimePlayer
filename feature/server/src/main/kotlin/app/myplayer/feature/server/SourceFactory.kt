package app.myplayer.feature.server

import app.myplayer.core.datastore.ServerConfig
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.datastore.TokenStore
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.SourceType
import app.myplayer.source.emby.EmbySource
import okhttp3.OkHttpClient

/**
 * 把「已保存的服务器配置」变成可用的 [MediaSource]，是 feature 层与 source 层之间**唯一**的装配点
 * （docs/02-技术规范.md §3.2）。放在 `:feature:server` 而不是 `:source:*`：
 * `:source:*` 之间不允许互相依赖，而这里同时知道 Emby 与（将来的）Jellyfin。
 */
class SourceFactory(
    private val client: OkHttpClient,
    internal val serverConfigStore: ServerConfigStore,
    internal val tokenStore: TokenStore,
    private val deviceId: String,
) {
    /**
     * 令牌或 userId 缺失时返回 **null**（= 需要重新登录），
     * 而不是造一个会在第一次请求时抛 `requireNotNull(userId)` 的半成品 source。
     */
    fun create(config: ServerConfig): MediaSource? {
        val token = tokenStore.get(config.id) ?: return null
        val userId = config.userId ?: return null
        return when (config.sourceType) {
            SourceType.EMBY -> EmbySource.create(
                client = client,
                baseUrl = config.baseUrl,
                serverId = config.id,
                token = token,
                userId = userId,
                deviceId = deviceId,
            )
            // JELLYFIN 在 M4 接入；LOCAL 在 L2。M1 不会走到这里。
            else -> null
        }
    }
}
