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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration

/**
 * 飞牛影视源（`docs/05` §6.9 实测链路）。
 *
 * 与 Emby 的差异：**全部业务接口需要 authx 签名**，且部分接口是 POST（载荷在 body）。
 * 播放地址来自 `play/play` 的 `play_link`（相对路径，HLS 转码流），拼上 baseUrl 即可播。
 */
class FnSource(
    private val client: FnClient,
    private val baseUrl: String,
    override val serverId: String,
) : MediaSource {

    override val type: SourceType = SourceType.FNOS

    /** play/info 的会话上下文：play/play 与 play/record 都要用。 */
    private data class Session(
        val itemGuid: String,
        val mediaGuid: String,
        val videoGuid: String,
        val audioGuid: String,
        val resumeSec: Int,
    )

    private val sessions = mutableMapOf<String, Session>()
    private var durationSec: Int = 0

    private suspend fun playInfo(itemGuid: String): Session {
        val resp = client.post("/v/api/v1/play/info", buildJsonObject { put("item_guid", itemGuid) })
        val data = resp["data"]?.jsonObject ?: throw FnException("play/info 返回为空")
        val s = Session(
            itemGuid = data.str("guid") ?: itemGuid,
            mediaGuid = data.str("media_guid").orEmpty(),
            videoGuid = data.str("video_guid").orEmpty(),
            audioGuid = data.str("audio_guid").orEmpty(),
            resumeSec = data.int("ts") ?: 0,
        )
        sessions[itemGuid] = s
        return s
    }

    override suspend fun getLibraries(): List<MediaLibrary> {
        val resp = client.get("/v/api/v1/mediadb/list")
        val list = resp.arr("data") ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val guid = o.str("guid") ?: return@mapNotNull null
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
        val resp = client.post("/v/api/v1/item/list", body)
        val data = resp["data"]?.jsonObject ?: return PagedResult(emptyList(), 0, 0)
        val total = data.int("total") ?: 0
        val items = data.arr("list").orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val guid = o.str("guid") ?: return@mapNotNull null
            MediaItemDto(
                id = guid,
                serverId = serverId,
                sourceType = SourceType.FNOS,
                title = o.str("title").orEmpty(),
                seasonNumber = o.int("season_number")?.takeIf { it > 0 },
                episodeNumber = o.int("episode_number")?.takeIf { it > 0 },
                userData = UserData(isPlayed = (o.int("watched") ?: 0) > 0),
                hasPoster = (o.arr("poster_list")?.size ?: 0) > 0,
            )
        }
        return PagedResult(items, total, 0)
    }

    override suspend fun getItemDetail(id: String): MediaItemDto {
        val s = playInfo(id)
        durationSec = 0
        return MediaItemDto(
            id = id,
            serverId = serverId,
            sourceType = SourceType.FNOS,
            title = id,
            userData = UserData(resumePositionTicks = Ticks(s.resumeSec * 10_000_000L)),
        )
    }

    override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource =
        ImageSource.None   // 海报路径规则未确认，暂用本地抽帧兜底（任务 3-7）

    override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan {
        val s = sessions[item.id] ?: playInfo(item.id)
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
        val resp = client.post("/v/api/v1/play/play", body)
        val link = resp["data"]?.jsonObject?.str("play_link")
            ?: throw FnException("play/play 未返回 play_link")
        val abs = if (link.startsWith("http")) link else baseUrl.trimEnd('/') + link
        return PlaybackPlan.Direct(uri = abs, mediaSourceId = s.mediaGuid)
    }

    override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null

    override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) {
        val s = sessions[item.id] ?: return
        runCatching {
            client.post(
                "/v/api/v1/play/record",
                buildJsonObject {
                    put("item_guid", s.itemGuid)
                    put("media_guid", s.mediaGuid)
                    put("video_guid", s.videoGuid)
                    put("audio_guid", s.audioGuid)
                    put("subtitle_guid", "")
                    put("resolution", "original")
                    put("bitrate", 0)
                    put("ts", pos.inWholeSeconds.toInt())
                    put("duration", durationSec)
                    put("device_id", serverId)
                    put("direct_link_audio_index", 0)
                    put("lan", "zh-CN")
                    put("device_name", "LimePlayer")
                },
            )
        }
    }
}
