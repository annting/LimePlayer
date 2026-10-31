package app.myplayer.feature.server

import app.myplayer.core.datastore.ServerConfig
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.datastore.TokenStore
import app.myplayer.core.model.FileCatalogStore
import app.myplayer.core.model.HidePrivate
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.PrivacyVisibilityPolicy
import app.myplayer.core.model.PlaybackStore
import app.myplayer.core.model.SourceType
import app.myplayer.source.emby.EmbySource
import app.myplayer.source.jellyfin.JellyfinSource
import app.myplayer.source.fnos.FnClient
import app.myplayer.source.fnos.FnSource
import app.myplayer.source.smb.SmbConfig
import app.myplayer.source.smb.SmbMediaSource
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
    /** 本地进度（SMB 起播读断点、首页「继续观看」）；null = 不启用本地进度（测试/预览）。 */
    private val playbackStore: PlaybackStore? = null,
    /** 文件条目库存（SMB 探测结果「取一次存库」）；null = 不入库。 */
    private val fileCatalog: FileCatalogStore? = null,
    /** 档二可见性：SMB 列目录时按它决定要不要隐藏标了隐私的文件。 */
    private val privacy: PrivacyVisibilityPolicy = HidePrivate,
) {
    /**
     * 令牌或 userId 缺失时返回 **null**（= 需要重新登录），
     * 而不是造一个会在第一次请求时抛 `requireNotNull(userId)` 的半成品 source。
     * 例外：飞牛影视的接口不需要 userId（签名 + token 即可），所以只校验令牌。
     */
    fun create(config: ServerConfig): MediaSource? {
        val token = tokenStore.get(config.id) ?: return null
        // SMB：token 里存的是加密后的「账号|密码|域」，baseUrl 里是 `smb://host:445/share`
        if (config.sourceType == SourceType.SMB) {
            val credentials = SmbCredentials.decode(token) ?: return null
            val smb = SmbConfig.parse(
                rawUrl = config.baseUrl,
                domain = credentials.domain,
                username = credentials.username,
                password = credentials.password,
            ) ?: return null
            return SmbMediaSource(
                config = smb,
                serverId = config.id,
                progress = playbackStore,
                catalog = fileCatalog,
                privacy = privacy,
            )
        }
        if (config.sourceType == SourceType.FNOS) {
            return FnSource(
                client = FnClient(baseUrl = config.baseUrl).also { it.setToken(token) },
                baseUrl = config.baseUrl,
                serverId = config.id,
            )
        }
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
            SourceType.JELLYFIN -> JellyfinSource.create(
                client = client,
                baseUrl = config.baseUrl,
                serverId = config.id,
                token = token,
                userId = userId,
                deviceId = deviceId,
                // 前缀留空；飞牛 / 反代实测有前缀时在 ServerConfig 加字段后传进来（`02` §R4）
                prefix = "",
            )
            // FNOS / SMB 已在上面提前返回；LOCAL 在 L2。
            else -> null
        }
    }
}
