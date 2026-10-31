package app.myplayer.core.model

/**
 * 本地播放断点（`docs/10-数据存储方案.md` §6.5）。位置与时长一律**毫秒**。
 *
 * 只服务**文件型源**（SMB / 将来的本地）：在线源（Emby / Jellyfin / 飞牛）的断点
 * 是服务端权威值，不写这张表（决策 D1 + ②「不混排」）。
 */
data class PlaybackProgress(
    val sourceId: String,
    /** 文件源 = 相对路径（`Movies/沙丘.mkv`）。 */
    val mediaKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val playCount: Int,
    val lastPlayedAt: Long,
)

/**
 * 播放进度的读写入口。
 *
 * **谁调用**：播放层（`PlayerViewModel`）写、源与页面读。
 * **谁实现**：`:core:database`（Room 唯一实现）。
 *
 * 设计约束（D1）：`MediaSource.reportProgress` 继续只负责**远端上报**；
 * 本地落库走这里，所以 SMB / 本地 / 将来 WebDAV 不必各自实现一遍进度存储，
 * 且 `:source:*` 永远不依赖 Room（只依赖本接口）。
 */
interface PlaybackStore {
    /**
     * 覆盖写断点（幂等）。会话中每 10s、暂停、退页各调一次。
     *
     * [completed] 为 true 时按不变量把位置清零（看完的片子不该挂在「继续观看」里）。
     */
    suspend fun recordPosition(
        sourceId: String,
        mediaKey: String,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean = false,
    )

    suspend fun progressOf(sourceId: String, mediaKey: String): PlaybackProgress?

    /** 批量取断点：列表页/详情页一次拿全，避免 N 次查询。 */
    suspend fun progressOf(sourceId: String, mediaKeys: Collection<String>): Map<String, PlaybackProgress>

    /** 「继续观看」：未看完且位置 > 0，按最后播放时间倒序。 */
    suspend fun continueWatching(sourceId: String, limit: Int = 20): List<PlaybackProgress>

    /** 清除断点（如「标记未看」）。 */
    suspend fun clear(sourceId: String, mediaKey: String)

    // ---- 观看历史（append-only 事实表，docs/10 §6.6）----

    /**
     * 开启一次播放会话，返回 `sessionId`。
     *
     * 起播成功（首个 PLAYING）时调用；**同一会话重复调用要避免**（调用方持有 id）。
     * [title] 会作为 `titleSnapshot` 冗余存一份 —— 文件删了/改名了历史仍然可读。
     */
    suspend fun beginSession(
        sourceId: String,
        mediaKey: String,
        title: String,
        startPositionMs: Long,
        durationMs: Long,
        playMethod: String? = null,
    ): String

    /**
     * 闭合会话：一个事务里「补 endedAt + 更新 playback_progress」。
     *
     * 分成两条 SQL 必须同事务，否则会出现「历史闭合了但断点没更新」（§7.3）。
     * [sessionId] 为 null 或找不到时只更新进度（兜底：起播期退出没有会话）。
     */
    suspend fun endSession(
        sessionId: String?,
        sourceId: String,
        mediaKey: String,
        endPositionMs: Long,
        durationMs: Long,
        completed: Boolean,
        reason: SessionEndReason,
    )

    /** 「最近看过」：**按条目去重**（每条取最近一次会话），按时间倒序。 */
    suspend fun recentWatched(sourceId: String, limit: Int = 50): List<WatchRecord>

    /**
     * 未闭合会话修复（进程被杀留下的 `endedAt = null`）。
     *
     * 用该条目最新断点补 `endPositionMs` + `endedAt`，`endedReason = APP_KILLED`；
     * **只修最近 [withinMs]** 的，更早的直接标 `UNKNOWN`（不逐条猜）。返回修复条数。
     */
    suspend fun repairOpenSessions(nowMs: Long, withinMs: Long = 7L * 24 * 3600 * 1000): Int
}

/** 结束会话的原因（写进 `watch_history.endedReason`）。 */
enum class SessionEndReason {
    /** 用户主动返回（最常见）。 */
    USER_EXIT,

    /** 正常播完。 */
    ENDED,

    /** 播放失败 / 源错误。 */
    ERROR,

    /** 进程被杀后由修复任务补的。 */
    APP_KILLED,

    /** 太老的未闭合会话，无法判断。 */
    UNKNOWN,
}

/** 一条观看记录（`recentWatched` / 历史页的数据单元）。 */
data class WatchRecord(
    val sessionId: String,
    val sourceId: String,
    val mediaKey: String,
    /** 会话开始时的标题快照。 */
    val title: String,
    val startedAt: Long,
    /** null = 会话未闭合（还没被修复任务补上）。 */
    val endedAt: Long?,
    val startPositionMs: Long,
    val endPositionMs: Long?,
    val durationMs: Long,
    val completed: Boolean,
    val endedReason: SessionEndReason?,
) {
    /** 上次看到的位置：未闭合时退回起播位置。 */
    val lastPositionMs: Long get() = endPositionMs ?: startPositionMs
}

/**
 * 扫描到的文件（登记用）。刻意**不含**探测字段 —— 登记不得覆盖已探测的时长/编码。
 */
data class FileSeen(
    val sourceId: String,
    /** 相对源根，`/` 分隔。 */
    val path: String,
    val name: String,
    val title: String,
    val sizeBytes: Long,
    val fileModifiedAt: Long,
    /** 顶层分类目录（=「库」），如 `Movies`。 */
    val libraryFolder: String?,
)

/** 探测状态：区分「没探过」和「探过了但容器读不出来」，后者不再重试（D6）。 */
enum class ProbeState { NONE, OK, UNSUPPORTED, FAILED }

/** 库内一条文件记录（含已入库的探测结果）。 */
data class FileEntry(
    val sourceId: String,
    val path: String,
    val name: String,
    val title: String,
    val sizeBytes: Long,
    val fileModifiedAt: Long,
    val firstSeenAt: Long,
    val libraryFolder: String?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val audioCodec: String?,
    val bitrate: Int?,
    val probeState: ProbeState,
    /** 扫描不到的时刻（ms，只标不删）；null = 本轮扫描还看得见。 */
    val missingAt: Long? = null,
    /** 档二 · 文档级隐私（`docs/隐私模式方案.md` §三）。普通模式下这种条目根本查不出来。 */
    val isPrivate: Boolean = false,
)

/**
 * 文件型条目的库存读写（`file_media_items`）。
 *
 * **谁调用**：`:source:smb`（列目录时登记、详情页读回探测结果、探测后写回）。
 * **谁实现**：`:core:database`。
 *
 * 关键不变量：[recordSeen] **不会**改写已存在的 `firstSeenAt`（D5「昨日新增」口径），
 * 也不会覆盖已写入的探测结果（否则每次重扫都要重读文件头）。
 *
 * **隐私**：所有**读**方法按实现方注入的 `PrivacyVisibilityPolicy` 过滤档二私有文档
 * （普通模式下这些条目查不出来，也不需要调用方各自过滤）；写方法
 * （[recordSeen] / [recordProbe] / [markMissing] / [setPrivate]）与模式无关 ——
 * 扫描、探测、标记都是「后台事实」，不是「现在给谁看」。
 */
interface FileCatalogStore {
    /** 登记一批扫描到的文件（已存在则只刷新会变的列）。 */
    suspend fun recordSeen(entries: List<FileSeen>)

    suspend fun entryOf(sourceId: String, path: String): FileEntry?

    suspend fun entriesOf(sourceId: String, paths: Collection<String>): Map<String, FileEntry>

    /**
     * 这批 path 里**哪些被标了隐私**（只回路径，不回标题/时长等任何内容字段）。
     *
     * 为什么需要它：[entriesOf] 按可见性策略过滤，普通模式下私有行**查不出来** →
     * 列举层只看得到「没有这条记录」，分不清「没入库」和「是隐私」。
     * 而列举层恰恰要做这个判断：SMB 目录是实时读的，必须在普通模式下把私有文件挡掉。
     * 返回集合只暴露「这个用户刚看到的路径是私有的」这一位信息，不构成内容泄露。
     */
    suspend fun privatePaths(sourceId: String, paths: Collection<String>): Set<String> = emptySet()

    /**
     * 当前被标为隐私的**目录**（相对路径集合）。
     *
     * 递归语义由实现方保证：[privatePaths] 对「路径落在私有目录下」的文件也返回命中，
     * 所以调用方只需要一个 [privatePaths] 就能同时拿到文件级与目录级的结果；
     * 这个方法单独暴露是给「目录数量级」的调用方（库列表过滤 / 首页判定）省一次前缀计算。
     */
    suspend fun privateFolders(sourceId: String): Set<String> = emptySet()

    /**
     * 目录级隐私标记（递归隐藏）：标中的目录下**所有**文件（含子目录）都被视为私有，
     * 之后新落进来的文件也自动被盖住（查询时前缀匹配，不写继承标记）。
     *
     * 与 [setPrivate] 一样不受当前模式限制。返回受影响目录数。
     */
    suspend fun setFolderPrivate(
        sourceId: String,
        paths: Collection<String>,
        isPrivate: Boolean,
    ): Int = 0

    /** 写探测结果（汇总列 + 多轨明细，整体替换）；[info] 为 null 时只写状态。 */
    suspend fun recordProbe(
        sourceId: String,
        path: String,
        info: MediaProbeInfo?,
        state: ProbeState,
    )

    /**
     * 读回已入库的探测结果。返回 null = 还没探过 / 探不出来 ⇒ 调用方按 [FileEntry.probeState] 决定
     * 是「去探一次」还是「不再重试」。
     */
    suspend fun probeOf(sourceId: String, path: String): MediaProbeInfo?

    /** 按首次发现倒序（「最近添加」/「昨日新增」）。 */
    suspend fun newest(sourceId: String, limit: Int): List<FileEntry>

    /**
     * 「昨日新增」列表：`firstSeenAt ∈ [fromMs, toMs)`（按**首次发现**，D5）。
     *
     * 区间由调用方按本地时区算好再传 —— 库里不存日期字符串（§13 风险 6）。
     */
    suspend fun newlyAdded(sourceId: String, fromMs: Long, toMs: Long): List<FileEntry>

    /** 「昨日新增」统计：条数 / 字节 / 时长，按 kind 分组（本期只会返回 `VIDEO` 一组）。 */
    suspend fun newlyAddedStats(sourceId: String, fromMs: Long, toMs: Long): List<NewlyAddedStat>

    /**
     * 全量扫描结束后，把「本轮没看见」的条目打上 `missingAt`（**只标不删**）。
     *
     * [scanStartedAt] 是本次扫描开始的时间戳：`lastSeenAt < scanStartedAt` 的才标。
     * **扫描失败（SMB 断连）时绝不能调** —— 否则等于「整个库都没了」。
     * 返回受影响行数。
     */
    suspend fun markMissing(sourceId: String, scanStartedAt: Long): Int

    /**
     * 档二：把一批文件标为隐私 / 取消隐私。
     *
     * **不受当前隐私模式限制**：只有隐私模式下 UI 才看得到私有条目，普通模式拿不到这些 path。
     * 返回受影响行数（path 不存在 / 已被重新扫描掉的行不计）。
     */
    suspend fun setPrivate(sourceId: String, paths: Collection<String>, isPrivate: Boolean): Int
}

/** 「昨日新增」的统计行。 */
data class NewlyAddedStat(
    val kind: String,
    val count: Int,
    val bytes: Long,
    val durationMs: Long,
)
