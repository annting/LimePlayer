package app.myplayer.source.httpapi

import app.myplayer.core.model.PagedResult
import app.myplayer.core.network.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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

    /**
     * 当前用户 —— **API Key 登录**用：拿 API Key 当令牌直接查 `/Users/Me`，
     * 既能校验 key 有效，又能拿到 `userId`（`MediaSource` 取数接口要求它非空）。
     */
    suspend fun getCurrentUser(): UserDto =
        execute(Request.Builder().url(url("/Users/Me")).applyAuth().build())

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
        // `DateCreated` 必须显式点名：Emby 的 `Fields` 是**白名单**，不写就真的不返回
        // （实测：库里 7 条缓存条目的 dateCreated 全为 null，首页「最近添加」按天过滤后整块消失）。
        // 详情页之所以一直有「添加日期」，是因为 `/Items/{id}` 走的是另一套完整投影。
        val fields = if (includePeople) {
            "MediaSources,UserData,Genres,People,DateCreated"
        } else {
            "MediaSources,UserData,Genres,DateCreated"
        }
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

    /**
     * 首页「继续观看」：`/Users/{userId}/Items/Resume`（Emby / Jellyfin 同端点）。
     *
     * 只取视频类（`MediaTypes=Video`）—— 音频续播不进首页；`Limit` 控制条数，
     * 卡片横滑行 20 条足够。`UserData` 必带（进度条与剩余时长都靠它）。
     */
    suspend fun getResumeItems(userId: String, limit: Int): List<BaseItemDto> =
        execute<ItemsResponseDto>(
            Request.Builder()
                .url(
                    url(
                        "/Users/$userId/Items/Resume",
                        mapOf(
                            "Limit" to limit.toString(),
                            "MediaTypes" to "Video",
                            "Recursive" to "true",
                            "Fields" to "MediaSources,UserData,Genres,DateCreated",
                        ),
                    )
                )
                .applyAuth().build()
        ).items

    /**
     * `/Users/{userId}/Items/Latest`（**首页已弃用**，见下方 [getRecentlyAdded]）。
     *
     * ⚠️ 该端点返回的是**裸数组**（`BaseItemDto[]`），不是 `{Items,TotalRecordCount}` ——
     * 用 [ItemsResponseDto] 解析会直接抛序列化异常。`GroupItems=false` 避免服务端把
     * 同一部剧的多集合并成一条（否则「最近添加」里全是剧集首集）。
     * 另一个坑：它会**漏库**（实测 5 个库只回 4 条，且全是老库），别拿来当「最近添加」的真源。
     */
    suspend fun getLatestItems(userId: String, limit: Int): List<BaseItemDto> =
        execute<List<BaseItemDto>>(
            Request.Builder()
                .url(
                    url(
                        "/Users/$userId/Items/Latest",
                        mapOf(
                            "Limit" to limit.toString(),
                            "GroupItems" to "false",
                            // DateCreated：首页「最近添加」按天过滤的唯一依据，必须显式点名
                            "Fields" to "MediaSources,UserData,Genres,DateCreated",
                        ),
                    )
                )
                .applyAuth().build()
        )

    /**
     * 首页「最近添加」（**主路径**）：`/Users/{userId}/Items` + 按入库时间倒序。
     *
     * 为什么不用 `/Items/Latest`：实测（Emby 4.10 / 2026-10-24）它会**漏库** ——
     * 5 个库只返回 4 条、全部来自两个老库，当天 01:49 刚入库的那个库一条都没有，
     * 于是「最近添加」在 7 天窗口里永远是空的（用户报的 bug）。
     * `/Items` + `SortBy=DateCreated&SortOrder=Descending` 语义等价且可预期。
     */
    suspend fun getRecentlyAdded(userId: String, limit: Int): List<BaseItemDto> =
        execute<ItemsResponseDto>(
            Request.Builder()
                .url(
                    url(
                        "/Users/$userId/Items",
                        mapOf(
                            "Recursive" to "true",
                            // 与 /Items/Latest 口径一致：非文件夹条目（剧集按「集」出，不按剧聚合）
                            "IncludeItemTypes" to "Movie,Episode,Video",
                            "SortBy" to "DateCreated",
                            "SortOrder" to "Descending",
                            "Fields" to "MediaSources,UserData,Genres,DateCreated",
                            "Limit" to limit.toString(),
                        ),
                    )
                )
                .applyAuth().build()
        ).items

    /** 详情查询：`Fields` 显式补 `Overview`（`05` §2.2：真机未下发时的兜底口径）。 */
    suspend fun getItemDetail(itemId: String, userId: String): BaseItemDto =
        execute(Request.Builder().url(url("/Users/$userId/Items/$itemId", mapOf("Fields" to "MediaSources,UserData,Genres,People,Overview"))).applyAuth().build())

    suspend fun getPlaybackInfo(itemId: String, userId: String, deviceProfileJson: String): PlaybackInfoResponseDto =
        execute(
            Request.Builder().url(url("/Items/$itemId/PlaybackInfo", mapOf("UserId" to userId)))
                .post(deviceProfileJson.toRequestBody("application/json".toMediaType()))
                .applyAuth().build()
        )

    /**
     * 某剧集下的全部集。
     * - 连播路径（[HttpMediaSource.getAdjacentEpisodes]）只需季号/集号，用默认参数即可（不带图，响应更小）；
     * - 详情页分季列表需要缩略图与简介，传 [withDetailFields] = true 打开 `EnableImages` 并补 `Overview`。
     */
    suspend fun getEpisodes(
        seriesId: String,
        userId: String,
        withDetailFields: Boolean = false,
    ): List<BaseItemDto> =
        execute<ItemsResponseDto>(
            Request.Builder()
                .url(
                    url(
                        "/Shows/$seriesId/Episodes",
                        mapOf(
                            "UserId" to userId,
                            "Fields" to if (withDetailFields) "MediaSources,UserData,Genres,Overview"
                            else "MediaSources,UserData,Genres",
                            "EnableImages" to withDetailFields.toString(),
                        ),
                    )
                )
                .applyAuth().build()
        ).items

    // ---- 详情页写操作与人物页（`05` §2.2，详情页二期）----

    /** 标记已看：返回服务端权威 `UserDataDto` 供本地缓存写回。 */
    suspend fun markPlayed(itemId: String, userId: String): UserDataDto =
        execute(
            Request.Builder()
                .url(url("/Users/$userId/PlayedItems/$itemId"))
                .post("{}".toRequestBody("application/json".toMediaType()))
                .applyAuth().build()
        )

    /** 取消已看：同路径 DELETE，返回同上。 */
    suspend fun unmarkPlayed(itemId: String, userId: String): UserDataDto =
        execute(
            Request.Builder()
                .url(url("/Users/$userId/PlayedItems/$itemId"))
                .delete()
                .applyAuth().build()
        )

    /** 删除条目：204 无响应体；无权限账号由服务端返回 401/403。 */
    suspend fun deleteItem(itemId: String) {
        withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder().url(url("/Items/$itemId")).delete().applyAuth().build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException.fromStatus(resp.code, resp.body.string())
            }
        }
    }

    /** 人物页作品列表（`PersonIds` 过滤；不含 People 重字段）。 */
    suspend fun getPersonWorks(personId: String, userId: String, limit: Int = 200): List<BaseItemDto> =
        execute<ItemsResponseDto>(
            Request.Builder()
                .url(
                    url(
                        "/Users/$userId/Items",
                        mapOf(
                            "Recursive" to "true",
                            "PersonIds" to personId,
                            "IncludeItemTypes" to "Movie,Series,Episode,Video",
                            "Fields" to "MediaSources,UserData,Genres",
                            "Limit" to limit.toString(),
                        ),
                    )
                )
                .applyAuth().build()
        ).items

    /** 人物头像 URL（与条目图片同端点形态；鉴权走请求头）。 */
    fun personImageUrl(personId: String, maxWidth: Int): String =
        imageUrl(personId, "Primary", maxWidth)

    // ---- trickplay（`05` §6；任务 3-1）----

    /** 分片清单 URL（不带令牌，鉴权走请求头）。 */
    fun trickplayPlaylistUrl(itemId: String, width: Int): String =
        url("/Videos/$itemId/Trickplay/$width/tiles.m3u8").toString()

    /** 单个分片图片 URL（`index` 从 0 起）。 */
    fun trickplayTileUrl(itemId: String, width: Int, index: Int): String =
        url("/Videos/$itemId/Trickplay/$width/$index.jpg").toString()

    /** 拉取分片清单原文；服务端未预生成时返回 404（由调用方降级为 null）。 */
    suspend fun getTrickplayPlaylist(itemId: String, width: Int): String =
        withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder().url(trickplayPlaylistUrl(itemId, width)).applyAuth().build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException.fromStatus(resp.code, resp.body.string())
                resp.body.string()
            }
        }

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

    /**
     * 外挂字幕地址（任务：字幕渲染）：服务端按需转 `format` 格式（默认 srt），
     * 鉴权与直放一致走 api_key 查询参数。
     */
    fun subtitleStreamUrl(itemId: String, mediaSourceId: String, subtitleIndex: Int, apiKey: String?): String =
        url("/Videos/$itemId/$mediaSourceId/Subtitles/$subtitleIndex/Stream.srt", buildMap {
            apiKey?.let { put("api_key", it) }
        }).toString()

    private fun Request.Builder.applyAuth(): Request.Builder =
        apply { authHeaders().forEach { (k, v) -> addHeader(k, v) } }

    // ---- 进度上报（`02` §6.4）----

    /** 开始播放。 */
    suspend fun reportPlaybackStart(request: PlaybackReportRequest) = postReport("/Sessions/Playing", request)

    /** 播放中进度（每 10s）或暂停。 */
    suspend fun reportPlaybackProgress(request: PlaybackReportRequest) = postReport("/Sessions/Playing/Progress", request)

    /** 结束播放。 */
    suspend fun reportPlaybackStopped(request: PlaybackReportRequest) = postReport("/Sessions/Playing/Stopped", request)

    /** 上报接口返回 204 No Content（无响应体），只需判状态码。 */
    private suspend fun postReport(path: String, request: PlaybackReportRequest) {
        val body = json.encodeToString(request)
        android.util.Log.d("LimeReport", "POST $path body=$body")
        withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url(url(path))
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .applyAuth()
                    .build()
            ).execute().use { resp ->
                android.util.Log.d("LimeReport", "→ $path ${resp.code}")
                if (!resp.isSuccessful) throw ApiException.fromStatus(resp.code, resp.body.string())
            }
        }
    }

    private fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
}
