package app.myplayer.feature.server

import app.myplayer.core.network.ApiException
import app.myplayer.source.httpapi.HttpMediaApi
import app.myplayer.source.httpapi.SourceConfig
import okhttp3.OkHttpClient

/**
 * 探测服务器：走公开端点 `/System/Info/Public`，不带令牌。
 * 404 → [ProbeStatus.NotFound]（端口 / 路径前缀不对）；其余异常 → [ProbeStatus.Unreachable]。
 */
suspend fun probeServer(client: OkHttpClient, baseUrl: String, port: String): ProbeStatus = try {
    val api = HttpMediaApi(client, SourceConfig(serverId = "probe", baseUrl = baseUrl, token = null, userId = null))
    val info = api.getPublicSystemInfo()
    ProbeStatus.Reachable(info.serverName ?: baseUrl, info.productName, info.version)
} catch (e: ApiException.NotFound) {
    ProbeStatus.NotFound
} catch (e: Exception) {
    ProbeStatus.Unreachable(e.message ?: e::class.java.simpleName)
}
