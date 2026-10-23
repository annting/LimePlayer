package app.myplayer.source.fnos

import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.ItemQuery
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.PagedResult
import app.myplayer.core.model.PlayState
import app.myplayer.core.model.PlaybackPlan
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.TrickplaySpec
import app.myplayer.core.model.UserData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration

/**
 * 飞牛影视源（`docs/05` §6.9 实测链路）。
 *
 * 与 Emby 的关键差异：
 * 1. **所有业务接口带 authx 签名**，且列表 / 播放接口是 POST（载荷在 body）。
 * 2. 媒体库里的电影常见形态是 `type=Directory` 的「文件夹条目」，真正的可播对象是它的
 *    `play_item_guid`（叶节点）。所以 [getItemDetail] 会**下钻一层**拿到 media/video/audio guid
 *    —— 列表仍显示文件夹标题（「跆拳道」），播放走叶节点（`VID_xxx.mp4`）。
 * 3. 海报是相对路径，真实地址为 `{baseUrl}/v/api/v1/sys/img{path}?w=`（鉴权头同 API）。
 */
class FnSource(
    private val client: FnClient,
    private val baseUrl: String,
    override val serverId: String,
) : MediaSource {

    override val type: SourceType = SourceType.FNOS

    /** 一次播放会话所需的 guid（play/play 与 play/record 都要用）。 */
    private data class Session(
        val playableGuid: String,
        val mediaGuid: String,
        val videoGuid: String,
        val audioGuid: String,
        val resumeSec: Int,
        val durationSec: Int,
    )

    // 会话与元数据缓存：键既可能是「文件夹条目 id」，也可能是叶节点 id
    private val sessions = mutableMapOf<String, Session>()
    private val playableOf = mutableMapOf<String, String>()
    private val posterOf = mutableMapOf<String, String>()
    private val durationOf = mutableMapOf<String, Int>()
    /** 播放地址短期缓存：`play/play` 有服务端副作用（起转码会话），不能反复调用。 */
    private val planCache = mutableMapOf<String, Pair<PlaybackPlan.Direct, Long>>()

    private suspend fun itemJson(guid: String): JsonObject =
        client.get("/v/api/v1/item/$guid")["data"]?.jsonObject
            ?: throw FnException("item/$guid 返回为空")

    /**
     * 解析「打开某条目」所需的全部信息：下钻到可播叶节点 + play/info 拿流 guid。
     * 返回 （会话, 展示用元数据）。
     */
    private suspend fun resolvePlayInfo(itemId: String): Pair<Session, JsonObject> {
        // 1) 条目自身（文件夹 or 视频）
        val self = runCatching { itemJson(itemId) }.getOrNull()
        val playable = self?.str("play_item_guid")?.takeIf { it.isNotBlank() }
            ?: playableOf[itemId]
            ?: itemId

        // 2) play/info：media/video/audio guid + 续播位置 + 真实时长
        val data = client.post("/v/api/v1/play/info", buildJsonObject { put("item_guid", playable) })["data"]
            ?.jsonObject ?: throw FnException("play/info 返回为空")
        val leaf = data["item"] as? JsonObject

        val session = Session(
            playableGuid = playable,
            mediaGuid = data.str("media_guid").orEmpty(),
            videoGuid = data.str("video_guid").orEmpty(),
            audioGuid = data.str("audio_guid").orEmpty(),
            // 续播位置：play/info 的 ts 可能为 null，服务端把进度记在 watched_ts 上
            resumeSec = data.int("ts") ?: self?.int("ts") ?: self?.int("watched_ts") ?: 0,
            durationSec = leaf?.int("duration") ?: self?.int("duration") ?: 0,
        )
        playableOf[itemId] = playable
        playableOf[playable] = playable
        sessions[itemId] = session
        sessions[playable] = session
        durationOf[itemId] = session.durationSec
        durationOf[playable] = session.durationSec

        // 3) 海报：文件夹优先，其次叶节点（两处字段名不同：poster_list / posters）
        val poster = self?.arr("poster_list")?.firstString()
            ?: leaf?.arr("posters")?.firstString()
            ?: leaf?.arr("poster_list")?.firstString()
        if (poster != null) {
            posterOf[itemId] = poster
            posterOf[playable] = poster
        }

        return session to (self ?: leaf ?: JsonObject(emptyMap()))
    }

    override suspend fun getLibraries(): List<MediaLibrary> {
        val list = client.get("/v/api/v1/mediadb/list").arr("data") ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val guid = o.str("guid") ?: return@mapNotNull null
            // 媒体库自身的海报也记下来（库卡片以后要用同一套图片接口）
            o.arr("posters")?.firstString()?.let { posterOf[guid] = it }
            MediaLibrary(id = guid, name = o.str("title").orEmpty(), collectionType = o.str("category"))
        }
    }

    override suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto> {
        val body = buildJsonObject {
            query.libraryId?.let { put("ancestor_guid", it) }
            put("page", 1)
            put("page_size", query.limit)
            put("sort_column", "create_time")
            put("sort_type", "DESC")
        }
        val data = client.post("/v/api/v1/item/list", body)["data"]?.jsonObject
            ?: return PagedResult(emptyList(), 0, 0)
        val total = data.int("total") ?: 0
        val items = data.arr("list").orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val guid = o.str("guid") ?: return@mapNotNull null
            val poster = o.arr("poster_list")?.firstString()
            if (poster != null) posterOf[guid] = poster
            MediaItemDto(
                id = guid,
                serverId = serverId,
                sourceType = SourceType.FNOS,
                title = o.str("title").orEmpty(),
                // 剧集：tv_title 为剧名，ancestor_guid 当剧集 id（连播取相邻集时需要）
                seriesName = o.str("tv_title")?.takeIf { it.isNotBlank() },
                seriesId = o.str("ancestor_guid")?.takeIf { it.isNotBlank() },
                seasonNumber = o.int("season_number")?.takeIf { it > 0 },
                episodeNumber = o.int("episode_number")?.takeIf { it > 0 },
                durationTicks = Ticks((o.int("duration") ?: 0) * TICKS_PER_SECOND),
                // 飞牛只给分辨率档位（"4k" / "1080p"…）：档位字符串直接展示，像素高度换算给排序用
                height = resolutionHeight(o),
                resolutionLabel = resolutionLabelOf(o),
                // vote_average 是字符串（未刮削为 "0"）；≤0 视为无评分
                communityRating = o.str("vote_average")?.toFloatOrNull()?.takeIf { it > 0f },
                libraryId = o.str("ancestor_guid"),
                hasPoster = poster != null,
                imageTags = if (poster != null) setOf("Primary") else emptySet(),
                playCount = o.int("watched") ?: 0,
                userData = UserData(
                    isPlayed = (o.int("watched") ?: 0) > 0,
                    resumePositionTicks = Ticks((o.int("ts") ?: 0) * TICKS_PER_SECOND),
                ),
            )
        }
        return PagedResult(items, total, 0)
    }

    /**
     * 首页「最近添加」：`item/list` 的默认排序就是 `create_time DESC`，不传 `ancestor_guid`
     * 即为**跨库**最新入库，与 Emby 的 `/Items/Latest` 语义近似。
     *
     * 「继续观看」暂不实现（走 [MediaSource.getResumeItems] 的默认空实现）：飞牛没有公开的
     * 续播列表端点，逐条 `play/info` 探测进度会真的起转码会话（`play/play` 堆积风险，
     * 见类注释），首页隐藏该模块比拖垮 NAS 更划算。
     */
    override suspend fun getLatestItems(limit: Int): List<MediaItemDto> =
        getItems(ItemQuery(limit = limit)).items

    override suspend fun getItemDetail(id: String): MediaItemDto {
        val (session, meta) = resolvePlayInfo(id)
        return MediaItemDto(
            id = id,
            serverId = serverId,
            sourceType = SourceType.FNOS,
            title = meta.str("title")?.takeIf { it.isNotBlank() } ?: id,
            seriesName = meta.str("tv_title")?.takeIf { it.isNotBlank() },
            seriesId = meta.str("ancestor_guid")?.takeIf { it.isNotBlank() },
            seasonNumber = meta.int("season_number")?.takeIf { it > 0 },
            episodeNumber = meta.int("episode_number")?.takeIf { it > 0 },
            durationTicks = Ticks(session.durationSec * TICKS_PER_SECOND),
            height = resolutionHeight(meta),
            resolutionLabel = resolutionLabelOf(meta),
            communityRating = meta.str("vote_average")?.toFloatOrNull()?.takeIf { it > 0f },
            libraryId = meta.str("ancestor_guid"),
            hasPoster = posterOf[id] != null,
            imageTags = if (posterOf[id] != null) setOf("Primary") else emptySet(),
            userData = UserData(
                isPlayed = (meta.int("is_watched") ?: meta.int("watched") ?: 0) > 0,
                resumePositionTicks = Ticks(session.resumeSec * TICKS_PER_SECOND),
            ),
        )
    }

    /**
     * 海报：`{baseUrl}/v/api/v1/sys/img{path}?w=<px>`，鉴权头与 API 相同（实测 `image/webp`）。
     * 路径未知时先读一次条目详情；仍没有就别请求（避免必然 404，`MediaItemDto.hasPoster` 的约定）。
     */
    override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource {
        if (!item.hasPoster && kind == ImageKind.POSTER) return ImageSource.None
        val path = posterOf[item.id] ?: runCatching { resolvePlayInfo(item.id) }
            .getOrNull()?.let { posterOf[item.id] }
            ?: return ImageSource.None
        val url = baseUrl.trimEnd('/') + "/v/api/v1/sys/img" +
            (if (path.startsWith("/")) path else "/$path") + "?w=${size.px}"
        return ImageSource.Remote(url, client.authHeaders())
    }

    override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan {
        // 短期缓存：`play/play` 会在服务端**真起一个转码会话**，同一集反复协商会堆积转码进程
        // （真机实测：缩略图 + 播放页重复协商把 NAS 拖到接口超时）。2 分钟内复用同一地址。
        planCache[item.id]?.let { (plan, at) ->
            if (System.currentTimeMillis() - at < PLAN_TTL_MS) return plan
        }
        val s = sessions[item.id] ?: resolvePlayInfo(item.id).first
        val body = buildJsonObject {
            put("media_guid", s.mediaGuid)
            put("video_guid", s.videoGuid)
            put("video_encoder", "h264")
            put("resolution", "original")
            put("bitrate", 0)
            put("startTimestamp", s.resumeSec)
            put("audio_encoder", "aac")
            put("audio_guid", s.audioGuid)
            put("subtitle_guid", "")
            put("channels", 2)
            put("forced_sdr", 0)
        }
        val t0 = System.currentTimeMillis()
        android.util.Log.d("LimeFnPlay", "play/play 请求 media=${s.mediaGuid.take(8)} ts=${s.resumeSec}")
        val resp = client.post("/v/api/v1/play/play", body)
        android.util.Log.d("LimeFnPlay", "play/play 返回 ${System.currentTimeMillis() - t0}ms")
        val link = resp["data"]?.jsonObject?.str("play_link")
            ?: throw FnException("play/play 未返回 play_link")
        // 相对路径（HLS 转码流），必须拼成绝对地址交给 ExoPlayer；
        // 且该流同样要鉴权 —— 不带 Authorization 会返回 200 + `{"code":-2,"msg":"Auth Failed"}`，
        // ExoPlayer 会把它当播放列表解析失败（真机实测：黑屏 00:00）。
        val abs = if (link.startsWith("http")) link else baseUrl.trimEnd('/') + link
        val plan = PlaybackPlan.Direct(
            uri = abs,
            headers = client.authHeaders(),
            mediaSourceId = s.mediaGuid,
        )
        android.util.Log.d("LimeFnPlay", "play_link=${abs.take(80)}")
        planCache[item.id] = plan to System.currentTimeMillis()
        return plan
    }

    override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null

    override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) {
        val s = sessions[item.id] ?: return
        // `play_link` 必传：缺省时接口仍返回 code:0，但进度不会落到条目的 watched_ts 上
        val link = planCache[item.id]?.first?.uri?.removePrefix(baseUrl)
            ?: planCache[playableOf[item.id]]?.first?.uri?.removePrefix(baseUrl)
        runCatching {
            client.post(
                "/v/api/v1/play/record",
                buildJsonObject {
                    put("item_guid", s.playableGuid)
                    put("media_guid", s.mediaGuid)
                    put("video_guid", s.videoGuid)
                    put("audio_guid", s.audioGuid)
                    put("subtitle_guid", "")
                    put("resolution", "original")
                    put("bitrate", 0)
                    put("ts", pos.inWholeSeconds.toInt())
                    put("duration", durationOf[item.id] ?: s.durationSec)
                    link?.let { put("play_link", it) }
                    put("device_id", serverId)
                    put("direct_link_audio_index", 0)
                    put("lan", "zh-CN")
                    put("device_name", "LimePlayer")
                },
            )
        }
    }

    /** 分辨率档位原串（"480p"/"1080p"/"4k"…），详情页直接展示。 */
    private fun resolutionLabelOf(o: JsonObject): String? =
        (o["media_stream"] as? JsonObject)?.arr("resolutions")?.firstString()

    private fun resolutionHeight(o: JsonObject): Int? {
        val res = resolutionLabelOf(o) ?: return null
        return when (res.lowercase()) {
            "4k", "2160p" -> 2160
            "1080p", "1080i" -> 1080
            "720p" -> 720
            "480p" -> 480
            else -> null
        }
    }

    private fun JsonArray.firstString(): String? =
        firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }

    private companion object {
        const val TICKS_PER_SECOND = 10_000_000L
        /** 播放地址缓存有效期：够覆盖「列表预览 + 进播放页」的连续请求，又不会长期用旧链接。 */
        const val PLAN_TTL_MS = 120_000L
    }
}
