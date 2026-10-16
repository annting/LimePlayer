package app.myplayer.source.httpapi

import app.myplayer.core.model.PagedResult
import app.myplayer.core.network.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class HttpMediaApi(
    private val client: OkHttpClient,
    val config: SourceConfig,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    private fun url(path: String, extra: Map<String, String> = emptyMap()): HttpUrl {
        val base = "${config.baseUrl}${config.prefix}".trimEnd('/')
        val builder = "$base$path".toHttpUrl().newBuilder()
        extra.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    /** 公开给 `HttpMediaSource.image()` 用：图片请求也走请求头鉴权，**不把令牌写进 URL**。 */
    fun authHeaders(): Map<String, String> {
        val token = config.token ?: return emptyMap()
        return when (config.authHeaderStyle) {
            AuthHeaderStyle.X_EMBY_TOKEN -> mapOf("X-Emby-Token" to token)
            AuthHeaderStyle.MEDIABROWSER_AUTHORIZATION ->
                mapOf("Authorization" to "MediaBrowser Token=\"$token\"")
        }
    }

    private fun clientInfoHeader(): String =
        "MediaBrowser Client=\"${config.clientName}\", Device=\"android\", DeviceId=\"${config.deviceId}\", Version=\"${config.clientVersion}\""

    private suspend inline fun <reified T> execute(request: Request): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) throw ApiException.fromStatus(resp.code, body)
            json.decodeFromString<T>(body)
        }
    }

    suspend fun getPublicSystemInfo(): PublicSystemInfoDto =
        execute(Request.Builder().url(url("/System/Info/Public")).build())

    suspend fun authenticateByName(username: String, password: String): AuthResultDto {
        val payload = """{"Username":"${username.esc()}","Pw":"${password.esc()}"}"""
        val request = Request.Builder()
            .url(url("/Users/AuthenticateByName"))
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("X-Emby-Authorization", clientInfoHeader())
            .build()
        return execute(request)
    }

    /**
     * 库列表：用 `/Users/{userId}/Views`（Emby/Jellyfin 的「媒体库根」专用端点）。
     * 不要用 `Items + IncludeItemTypes=CollectionFolder` 代替——那会把非媒体库的文件夹一起扫进来。
     * ⚠️ 飞牛是否支持该端点需在 M0 实测；若不支持，再在 `:source:jellyfin` 覆盖。
     */
    suspend fun getViews(): PagedResult<BaseItemDto> {
        val userId = requireNotNull(config.userId) { "登录后才能获取媒体库列表" }
        val dto = execute<ItemsResponseDto>(
            Request.Builder()
                .url(url("/Users/$userId/Views", mapOf("IncludeExternalContent" to "false")))
                .applyAuth().build()
        )
        return PagedResult(dto.items, dto.totalRecordCount, 0)
    }

    /**
     * 列表查询。`includePeople=false` 时不请求 `People` —— 演职员数组在数万条的库上是响应体积与解析耗时的主要来源，
     * 会直接抵消大库优化的意义；演职员等重字段留到 [getItemDetail]。
     */
    suspend fun getItems(
        libraryId: String?,
        startIndex: Int,
        limit: Int,
        types: List<String> = listOf("Movie", "Series", "Episode", "Video"),
        searchTerm: String? = null,
        includePeople: Boolean = false,
    ): PagedResult<BaseItemDto> {
        val userId = requireNotNull(config.userId) { "登录后才能取条目" }
        val fields = if (includePeople) "MediaSources,UserData,Genres,People" else "MediaSources,UserData,Genres"
        val extra = buildMap<String, String> {
            put("Recursive", "true")
            put("IncludeItemTypes", types.joinToString(","))
            put("Fields", fields)
            put("StartIndex", startIndex.toString())
            put("Limit", limit.toString())
            libraryId?.let { put("ParentId", it) }
            searchTerm?.let { put("SearchTerm", it) }
        }
        val dto = execute<ItemsResponseDto>(
            Request.Builder().url(url("/Users/$userId/Items", extra)).applyAuth().build()
        )
        return PagedResult(dto.items, dto.totalRecordCount, startIndex)
    }

    suspend fun getItemDetail(itemId: String, userId: String): BaseItemDto =
        execute(Request.Builder().url(url("/Users/$userId/Items/$itemId", mapOf("Fields" to "MediaSources,UserData,Genres,People"))).applyAuth().build())

    suspend fun getPlaybackInfo(itemId: String, userId: String, deviceProfileJson: String): PlaybackInfoResponseDto =
        execute(
            Request.Builder().url(url("/Items/$itemId/PlaybackInfo", mapOf("UserId" to userId)))
                .post(deviceProfileJson.toRequestBody("application/json".toMediaType()))
                .applyAuth().build()
        )

    /** 图片 URL **不带令牌**；鉴权走 [authHeaders]，由 Coil 的请求头注入。 */
    fun imageUrl(itemId: String, kind: String, maxWidth: Int): String =
        url("/Items/$itemId/Images/$kind", mapOf("maxWidth" to maxWidth.toString())).toString()

    /**
     * 直连播放地址。这里**有意**用 `api_key` 查询参数（官方形态）：
     * 播放 URL 会交给 ExoPlayer / HLS 播放列表，`api_key` 是最稳的形态；
     * 图片 URL 才改用请求头（见 [imageUrl]），因为图片是 Coil 直接拉、能带 header。
     */
    fun directStreamUrl(itemId: String, mediaSourceId: String, apiKey: String?): String =
        url("/Videos/$itemId/stream", buildMap {
            put("static", "true")
            put("MediaSourceId", mediaSourceId)
            apiKey?.let { put("api_key", it) }
        }).toString()

    private fun Request.Builder.applyAuth(): Request.Builder =
        apply { authHeaders().forEach { (k, v) -> addHeader(k, v) } }

    private fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
}
