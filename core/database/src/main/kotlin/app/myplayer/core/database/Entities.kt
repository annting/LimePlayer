package app.myplayer.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "libraries", primaryKeys = ["serverId", "libraryId"])
data class LibraryEntity(
    val serverId: String,
    val libraryId: String,
    val name: String,
    val collectionType: String?,
    val itemCount: Int?,
)

@Entity(
    tableName = "media_items",
    primaryKeys = ["serverId", "itemId"],
    indices = [Index("serverId"), Index("libraryId"), Index("sortTitle")],
)
data class MediaItemEntity(
    val serverId: String,
    val itemId: String,
    val sourceType: String,
    val title: String,
    val sortTitle: String,
    val seriesName: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    /** 单位：Emby/Jellyfin tick（100ns）。领域层用 `Ticks`，此处为 Room 边界，见 Mappers.kt 的显式转换。 */
    val durationTicks: Long,
    val sizeBytes: Long?,
    val bitrate: Int?,
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val audioCodecs: String,
    val container: String?,
    val dateCreated: Long?,
    val communityRating: Float?,
    val officialRating: String?,
    val playCount: Int,
    val isPlayed: Boolean,
    /** 单位同上。 */
    val resumePositionTicks: Long,
    val isFavorite: Boolean,
    val genres: String,
    val cast: String,
    val libraryId: String?,
    val hasPoster: Boolean,
    val trickplayAvailable: Boolean,
)

/**
 * 文件型源（SMB / 将来的本地）的条目库存（`docs/10-数据存储方案.md` §6.3）。
 *
 * 为什么不复用 [MediaItemEntity]：那张表是 Emby 形状（剧集/评分/演职员…），
 * 一半列对文件源恒为空；而且它没有 `path` 列（`path` 被定义为「详情专属、不同步入库」）。
 *
 * ⚠️ `firstSeenAt` 是「本机首次发现时间」，**只写一次**：新增用 `INSERT OR IGNORE`，
 * 更新走白名单 UPDATE（白名单里没有它），否则每次重扫都会把「昨日新增」抹成今天。
 */
@Entity(
    tableName = "file_media_items",
    primaryKeys = ["sourceId", "path"],
    indices = [
        Index(value = ["sourceId", "firstSeenAt"]),
        Index(value = ["sourceId", "libraryFolder", "sortTitle"]),
        // 档二隐私过滤的主查询形状：`WHERE sourceId = ? AND isPrivate = 0`
        Index(value = ["sourceId", "isPrivate"]),
        Index(value = ["probeState"]),
        Index(value = ["missingAt"]),
    ],
)
data class FileMediaItemEntity(
    val sourceId: String,
    /** 相对源根的路径，`/` 分隔，大小写敏感。 */
    val path: String,
    /** `VIDEO`；D7 决定本期不纳入音频，音频文件不会被扫描进来。 */
    val kind: String,
    val name: String,
    val title: String,
    val sortTitle: String,
    val sizeBytes: Long,
    /** 文件自身时间（ms）。 */
    val fileModifiedAt: Long,
    /** 本机首次发现（ms）。**只写一次**。 */
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    /** 扫描不到的时刻（ms）；非空 = 待清理。本期只标不删。 */
    val missingAt: Long?,
    /** 顶层分类目录（= 现有「库」概念，如 `Movies`）。 */
    val libraryFolder: String?,
    /** 以下为探测结果（未探测为 null）。 */
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val audioCodec: String?,
    val bitrate: Int?,
    val thumbPath: String?,
    /** `NONE` / `OK` / `UNSUPPORTED` / `FAILED`：区分「没探过」和「探过了读不出来」。 */
    val probeState: String,
    val probedAt: Long?,
    /**
     * **档二 · 文档/条目级隐私**（`docs/隐私模式方案.md` §三）。
     *
     * 与档一（`ServerConfig.isPrivate` 那侧的源级标记）**完全独立**：某个 SMB 源本身不是隐私，
     * 里面某几个视频也能单独标隐私。`DEFAULT 0` 让 v3→v4 迁移在旧库上零成本（全部视为可见）。
     *
     * ⚠️ 只负责「app 不列出」；文件仍在磁盘上，系统文件管理器依旧可见（§一 威胁模型）。
     */
    @ColumnInfo(defaultValue = "0")
    val isPrivate: Boolean = false,
)

/**
 * 本地播放断点（`docs/10-数据存储方案.md` §6.5）：每条目一行，**可覆盖**。
 *
 * 不复用 `media_items.resumePositionTicks`：那两列是服务端权威值，每次同步都会被服务器返回覆盖。
 *
 * 不变量：`completed = true` ⇒ `positionMs = 0`（看完清零，避免「继续观看」挂旧片）。
 */
@Entity(
    tableName = "playback_progress",
    primaryKeys = ["sourceId", "mediaKey"],
    indices = [Index(value = ["completed", "lastPlayedAt"])],
)
data class PlaybackProgressEntity(
    val sourceId: String,
    /** 文件源 = 相对路径；在线源不用这张表（服务端权威）。 */
    val mediaKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val playCount: Int,
    /** 最后一次播放（ms）→「继续观看」排序键。 */
    val lastPlayedAt: Long,
    val updatedAt: Long,
    val lastSessionId: String?,
)

/**
 * 探测出来的单条媒体流（`docs/10-数据存储方案.md` §6.4）。
 *
 * 为什么不只存「主轨汇总」：详情页要分别渲染「视频 / 音频 / 字幕信息」三个分区，
 * 一部 mkv 可能 3 条音轨 + 2 条字幕 —— 只存汇总的话，进详情仍然得读一次文件头，
 * 达不到「取一次存库」的目标（用户 2026-10-27 的明确要求）。
 *
 * `kind=AUDIO` 指**视频容器内的音轨**，与「独立音频文件」无关（D7 不纳入音频文件）。
 */
@Entity(
    tableName = "media_streams",
    primaryKeys = ["sourceId", "path", "kind", "streamIndex"],
    indices = [Index(value = ["sourceId", "path"])],
)
data class MediaStreamEntity(
    val sourceId: String,
    val path: String,
    /** `VIDEO` / `AUDIO` / `SUBTITLE`。 */
    val kind: String,
    /** 容器内轨道序号（与 `MediaExtractor` 的 track index 一致）。 */
    val streamIndex: Int,
    val codec: String?,
    val language: String?,
    val displayTitle: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Float?,
    val bitDepth: Int?,
    val channels: Int?,
    val sampleRate: Int?,
    val bitrate: Int?,
    val isExternal: Boolean,
    val isDefault: Boolean,
)

/**
 * 观看历史（`docs/10-数据存储方案.md` §6.6）：**append-only 事实表**，一次播放会话一行。
 *
 * 为什么不和 [PlaybackProgressEntity] 合并：进度是「当前状态」（可覆盖、一条目一行），
 * 历史是「事实」（不可改写、一条目可以几百行）。合并后「继续观看」每次都要聚合，
 * 而且看不完的记录会被下一次播放冲掉。
 *
 * ⚠️ 不设外键：条目可能被删 / 改名，[titleSnapshot] 冗余一份标题保证历史仍可读。
 */
@Entity(
    tableName = "watch_history",
    primaryKeys = ["sessionId"],
    indices = [
        Index(value = ["startedAt"]),
        Index(value = ["sourceId", "mediaKey", "startedAt"]),
        // 找未闭合会话（进程被杀）用
        Index(value = ["endedAt"]),
    ],
)
data class WatchHistoryEntity(
    val sessionId: String,
    val sourceId: String,
    val mediaKey: String,
    /** 冗余标题：文件删 / 改名后历史仍可读。 */
    val titleSnapshot: String,
    /** `VIDEO`（D7 本期不含音频）。 */
    val kind: String,
    val startedAt: Long,
    /** NULL = 会话未闭合（进程被杀），启动时由修复任务补上。 */
    val endedAt: Long?,
    val startPositionMs: Long,
    val endPositionMs: Long?,
    val durationMs: Long,
    val completed: Boolean,
    /** `DIRECT` / `TRANSCODE` / `PROXY`（SMB 恒 `PROXY`）。 */
    val playMethod: String?,
    /** `USER_EXIT` / `ENDED` / `ERROR` / `APP_KILLED` / `UNKNOWN`。 */
    val endedReason: String?,
)
