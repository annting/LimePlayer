package app.myplayer.feature.server

import app.myplayer.core.network.ApiException
import app.myplayer.source.httpapi.HttpMediaApi
import app.myplayer.source.httpapi.SourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 探测服务器：Emby / Jellyfin 走公开端点 `/System/Info/Public`，不带令牌。
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

/**
 * 探测 SMB：源模块还没接入，但**端口连通性是真的能测的** ——
 * 对 `host:445` 做一次 TCP 握手，能通说明地址 / 端口 / 防火墙都没问题。
 *
 * 不做 SMB 协议协商（那需要 SMB2 客户端），所以只回复「可达」，不报版本。
 */
suspend fun probeSmb(baseUrl: String): ProbeStatus = withContext(Dispatchers.IO) {
    val parsed = parseServerUrl(baseUrl)
    val host = parsed.host.ifBlank { return@withContext ProbeStatus.Unreachable("请填写服务器地址") }
    val port = parsed.port?.toIntOrNull() ?: 445
    try {
        Socket().use { socket -> socket.connect(InetSocketAddress(host, port), 3000) }
        ProbeStatus.Reachable(serverName = "$host:$port", productName = "SMB", version = null)
    } catch (e: Exception) {
        ProbeStatus.Unreachable("TCP $port 连不上：${e.message ?: e::class.java.simpleName}")
    }
}

/**
 * 探测飞牛影视（`docs/05` §6.9）：`GET /v/api/v1/sys/version` 是其**唯一免签名**的业务端点，
 * 返回 `{"code":0,"data":{"version":"0.9.8","mediasrvVersion":"0.8.41"}}`。
 *
 * 飞牛影视**没有** `/System/Info/Public`，所以不能复用 [probeServer]。
 */
suspend fun probeFnos(client: OkHttpClient, baseUrl: String): ProbeStatus = withContext(Dispatchers.IO) {
    try {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v/api/v1/sys/version")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            when {
                resp.code == 404 -> ProbeStatus.NotFound
                !resp.isSuccessful -> ProbeStatus.Unreachable("HTTP ${resp.code}")
                !text.contains("\"code\":0") -> ProbeStatus.Unreachable("该地址不是飞牛影视：${text.take(80)}")
                else -> {
                    val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                    val mediasrv = Regex("\"mediasrvVersion\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                    ProbeStatus.Reachable(
                        serverName = "飞牛影视",
                        productName = "fnOS Media",
                        version = listOfNotNull(version, mediasrv?.let { "media $it" })
                            .joinToString(" / ")
                            .ifEmpty { null },
                    )
                }
            }
        }
    } catch (e: Exception) {
        ProbeStatus.Unreachable(e.message ?: e::class.java.simpleName)
    }
}
