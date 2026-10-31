package app.myplayer.source.jellyfin

import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SourceType
import app.myplayer.source.emby.DeviceProfile
import app.myplayer.source.emby.EmbyMapper
import app.myplayer.source.httpapi.AuthHeaderStyle
import app.myplayer.source.httpapi.BaseItemDto
import app.myplayer.source.httpapi.HttpMediaApi
import app.myplayer.source.httpapi.HttpMediaSource
import app.myplayer.source.httpapi.SourceConfig
import okhttp3.OkHttpClient

/**
 * Jellyfin 源（任务 4-1）。
 *
 * 与 Emby 的差异只在**接入参数**，业务端点完全复用 [HttpMediaSource]：
 * - 鉴权头用 `Authorization: MediaBrowser Client=…, Device=…, DeviceId=…, Version=…, Token=…`
 *   （Jellyfin 官方形态；Emby 走 `X-Emby-Token`）
 * - 路径前缀默认空；[create] 暴露 [prefix] 以便飞牛 / 反代部署按实测结论覆盖（`02` §R4）
 * - 条目映射复用 [EmbyMapper]（同源分叉，字段口径一致）
 */
class JellyfinSource(
    api: HttpMediaApi,
    private val mapper: EmbyMapper,
) : HttpMediaSource(api) {

    override val type: SourceType = SourceType.JELLYFIN

    override fun mapItem(dto: BaseItemDto, libraryId: String?): MediaItemDto = mapper.map(dto, libraryId)

    override fun buildDeviceProfile(caps: ClientCapabilities): String = DeviceProfile.build(caps)

    companion object {
        fun create(
            client: OkHttpClient,
            baseUrl: String,
            serverId: String,
            token: String?,
            userId: String?,
            deviceId: String,
            prefix: String = "",
            clientName: String = "LimePlayer",
            clientVersion: String = "0.1.0",
        ): JellyfinSource = JellyfinSource(
            api = HttpMediaApi(
                client = client,
                config = SourceConfig(
                    serverId = serverId,
                    baseUrl = baseUrl,
                    prefix = prefix,
                    token = token,
                    userId = userId,
                    deviceId = deviceId,
                    clientName = clientName,
                    clientVersion = clientVersion,
                    authHeaderStyle = AuthHeaderStyle.MEDIABROWSER_AUTHORIZATION,
                ),
            ),
            mapper = EmbyMapper(serverId, SourceType.JELLYFIN),
        )
    }
}
