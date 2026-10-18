package app.myplayer.source.emby

import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SourceType
import app.myplayer.source.httpapi.AuthHeaderStyle
import app.myplayer.source.httpapi.BaseItemDto
import app.myplayer.source.httpapi.HttpMediaApi
import app.myplayer.source.httpapi.HttpMediaSource
import app.myplayer.source.httpapi.SourceConfig
import okhttp3.OkHttpClient

class EmbySource(
    api: HttpMediaApi,
    private val mapper: EmbyMapper,
) : HttpMediaSource(api) {

    override val type: SourceType = SourceType.EMBY

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
        ): EmbySource = EmbySource(
            api = HttpMediaApi(
                client = client,
                config = SourceConfig(
                    serverId = serverId, baseUrl = baseUrl, prefix = "", token = token,
                    userId = userId, deviceId = deviceId, authHeaderStyle = AuthHeaderStyle.X_EMBY_TOKEN,
                ),
            ),
            mapper = EmbyMapper(serverId),
        )
    }
}
