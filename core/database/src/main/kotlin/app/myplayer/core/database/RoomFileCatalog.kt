package app.myplayer.core.database

import androidx.room.withTransaction
import app.myplayer.core.model.FileCatalogStore
import app.myplayer.core.model.FileEntry
import app.myplayer.core.model.FileSeen
import app.myplayer.core.model.HidePrivate
import app.myplayer.core.model.MediaProbeInfo
import app.myplayer.core.model.MediaStreamInfo
import app.myplayer.core.model.NewlyAddedStat
import app.myplayer.core.model.PrivacyVisibilityPolicy
import app.myplayer.core.model.ProbeState
import app.myplayer.core.model.StreamKind
import app.myplayer.core.model.Ticks

/**
 * [FileCatalogStore] 的 Room 实现（`file_media_items`）。
 *
 * 两条写规则源自 `docs/10-数据存储方案.md` F3：
 * 1. 新增用 `INSERT OR IGNORE`，**保住 `firstSeenAt`**（否则重扫一次，「昨日新增」就全变成今天）；
 * 2. 已存在的行只走 `touch()` 的白名单 UPDATE，白名单里既没有 `firstSeenAt` 也没有探测字段。
 *
 * 分块事务（200 条/块）：一个目录几千个文件时，整目录一个大事务会长时间持锁，
 * 而按块提交在 SMB 断连重扫时也更容易观察进度。
 *
 * **隐私**：每个**读**方法都把 [privacy] 的当前值传给 DAO 的 `includePrivate`
 * （`docs/隐私模式方案.md` §三 P0#2）。调用方（`:source:*` / UI）拿不到这个开关，
 * 所以不可能「忘了过滤」—— 普通模式下私有文档对它们**根本不存在**。
 *
 * @param privacy 默认 [HidePrivate]（安全方向：漏显示是可见 bug，漏数据是事故）。
 * @param now 可注入时钟，单测固定「首次发现」时间用。
 */
class RoomFileCatalog(
    private val db: MediaDatabase,
    private val privacy: PrivacyVisibilityPolicy = HidePrivate,
    private val now: () -> Long = System::currentTimeMillis,
) : FileCatalogStore {

    override suspend fun recordSeen(entries: List<FileSeen>) {
        if (entries.isEmpty()) return
        val dao = db.fileMediaItemDao()
        val t = now()
        db.withTransaction {
            entries.chunked(INSERT_CHUNK).forEach { chunk ->
                dao.insertIgnore(chunk.map { it.toEntity(t) })
                // 命中已有行时刷新「会变」的列；firstSeenAt 与探测结果不受影响
                chunk.forEach { e ->
                    dao.touch(
                        sourceId = e.sourceId,
                        path = e.path,
                        name = e.name,
                        title = e.title,
                        sortTitle = e.title.lowercase(),
                        sizeBytes = e.sizeBytes,
                        fileModifiedAt = e.fileModifiedAt,
                        libraryFolder = e.libraryFolder,
                        now = t,
                    )
                }
            }
        }
    }

    override suspend fun entryOf(sourceId: String, path: String): FileEntry? =
        db.fileMediaItemDao().get(sourceId, path, privacy.includePrivate())?.toModel()

    override suspend fun entriesOf(
        sourceId: String,
        paths: Collection<String>,
    ): Map<String, FileEntry> {
        if (paths.isEmpty()) return emptyMap()
        val includePrivate = privacy.includePrivate()
        // SQLite 的绑定变量上限是 999，超过就分批查（一屏最多几十条，这里只是防御）
        return paths.toList().chunked(QUERY_CHUNK)
            .flatMap { db.fileMediaItemDao().getMany(sourceId, it, includePrivate) }
            .associate { it.path to it.toModel() }
    }

    override suspend fun recordProbe(
        sourceId: String,
        path: String,
        info: MediaProbeInfo?,
        state: ProbeState,
    ) {
        val t = now()
        val dao = db.fileMediaItemDao()
        // 汇总列取「主轨」：视频取第一条 VIDEO 流，音频取第一条 AUDIO 流。
        // 列表页的进度条只依赖 durationMs，其余汇总字段供详情页摘要与排序使用。
        val video = info?.streams?.firstOrNull { it.kind == StreamKind.VIDEO }
        val audio = info?.streams?.firstOrNull { it.kind == StreamKind.AUDIO }
        db.withTransaction {
            dao.updateProbe(
                sourceId = sourceId,
                path = path,
                durationMs = info?.durationTicks?.millis,
                width = video?.width,
                height = video?.height,
                videoCodec = video?.codec,
                audioCodec = audio?.codec,
                bitrate = video?.bitrate ?: audio?.bitrate,
                probeState = state.name,
                now = t,
            )
            // 明细整体替换：先删后插，避免上次 3 条音轨这次 2 条时残留
            val streamDao = db.mediaStreamDao()
            streamDao.deleteFor(sourceId, path)
            val rows = info?.streams?.mapIndexed { idx, s ->
                MediaStreamEntity(
                    sourceId = sourceId,
                    path = path,
                    kind = s.kind.name,
                    streamIndex = idx,
                    codec = s.codec,
                    language = s.language,
                    displayTitle = s.displayTitle,
                    width = s.width,
                    height = s.height,
                    frameRate = s.frameRate,
                    bitDepth = s.bitDepth,
                    channels = s.channels,
                    sampleRate = s.sampleRate,
                    bitrate = s.bitrate,
                    isExternal = s.isExternal,
                    isDefault = s.isDefault,
                )
            }.orEmpty()
            if (rows.isNotEmpty()) streamDao.insertAll(rows)
        }
    }

    override suspend fun probeOf(sourceId: String, path: String): MediaProbeInfo? {
        val entry = db.fileMediaItemDao().get(sourceId, path, privacy.includePrivate()) ?: return null
        // 只有 OK 才算「有可用技术信息」：NONE = 还没探过，UNSUPPORTED/FAILED = 探过但读不出来。
        // 后两者都返回 null，调用方靠 FileEntry.probeState 区分「该去探」还是「别再试」。
        if (runCatching { ProbeState.valueOf(entry.probeState) }.getOrDefault(ProbeState.NONE) != ProbeState.OK) {
            return null
        }
        val streams = db.mediaStreamDao().get(sourceId, path).map { it.toStreamInfo() }
        return MediaProbeInfo(
            streams = streams,
            durationTicks = entry.durationMs?.let { Ticks.ofMillis(it) } ?: Ticks.ZERO,
        )
    }

    override suspend fun newest(sourceId: String, limit: Int): List<FileEntry> =
        db.fileMediaItemDao().newest(sourceId, limit, privacy.includePrivate()).map { it.toModel() }

    override suspend fun newlyAdded(sourceId: String, fromMs: Long, toMs: Long): List<FileEntry> =
        db.fileMediaItemDao().newlyAdded(sourceId, fromMs, toMs, privacy.includePrivate())
            .map { it.toModel() }

    override suspend fun newlyAddedStats(
        sourceId: String,
        fromMs: Long,
        toMs: Long,
    ): List<NewlyAddedStat> =
        db.fileMediaItemDao().newlyAddedStats(sourceId, fromMs, toMs, privacy.includePrivate()).map {
            NewlyAddedStat(
                kind = it.kind,
                count = it.count,
                bytes = it.bytes ?: 0L,
                durationMs = it.durationMs ?: 0L,
            )
        }

    override suspend fun markMissing(sourceId: String, scanStartedAt: Long): Int =
        db.fileMediaItemDao().markMissing(sourceId, scanStartedAt, now())

    override suspend fun privatePaths(sourceId: String, paths: Collection<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        val list = paths.toList()
        val dao = db.fileMediaItemDao()
        val direct = buildSet {
            list.chunked(QUERY_CHUNK).forEach { chunk ->
                addAll(dao.privatePaths(sourceId, chunk))
            }
        }
        // 目录级递归（2026-10-29）：命中私有目录（自身或子路径）的文件也算私有。
        // 查一次全量目录集（量级 = 用户标的文件夹数，个位数）再在内存里做前缀匹配。
        val folders = dao.privateFolders(sourceId)
        if (folders.isEmpty()) return direct
        return buildSet {
            addAll(direct)
            list.forEach { p ->
                if (folders.any { f -> p == f || p.startsWith("$f/") }) add(p)
            }
        }
    }

    override suspend fun privateFolders(sourceId: String): Set<String> =
        db.fileMediaItemDao().privateFolders(sourceId).toSet()

    override suspend fun setFolderPrivate(
        sourceId: String,
        paths: Collection<String>,
        isPrivate: Boolean,
    ): Int {
        if (paths.isEmpty()) return 0
        val dao = db.fileMediaItemDao()
        return db.withTransaction {
            if (isPrivate) {
                // 标记 = upsert 一行 kind=FOLDER（sizeBytes=0、探测字段全空）。
                // 不动目录下文件行的 isPrivate：递归隐藏靠查询时的前缀匹配，
                // 取消后新文件不会残留旧标记，也不需要逐文件回滚。
                val t = now()
                paths.toList().chunked(QUERY_CHUNK).forEach { chunk ->
                    dao.upsertFolders(chunk.map { folderEntity(sourceId, it, t) })
                }
                paths.size
            } else {
                var total = 0
                paths.toList().chunked(QUERY_CHUNK).forEach { chunk ->
                    total += dao.deleteFolders(sourceId, chunk)
                }
                total
            }
        }
    }

    /** 目录行：name/title 取路径最后一段（与文件行的取法一致）。 */
    private fun folderEntity(sourceId: String, path: String, t: Long) = FileMediaItemEntity(
        sourceId = sourceId,
        path = path,
        kind = KIND_FOLDER,
        name = path.substringAfterLast('/'),
        title = path.substringAfterLast('/'),
        sortTitle = path.substringAfterLast('/').lowercase(),
        sizeBytes = 0L,
        fileModifiedAt = t,
        firstSeenAt = t,
        lastSeenAt = t,
        missingAt = null,
        libraryFolder = path.substringBefore('/', "").takeIf { it.isNotBlank() },
        durationMs = null,
        width = null,
        height = null,
        videoCodec = null,
        audioCodec = null,
        bitrate = null,
        thumbPath = null,
        probeState = ProbeState.NONE.name,
        probedAt = null,
        isPrivate = true,
    )

    override suspend fun setPrivate(
        sourceId: String,
        paths: Collection<String>,
        isPrivate: Boolean,
    ): Int {
        if (paths.isEmpty()) return 0
        // 一个事务里批量标：长按多选可能一次标几十条，逐条提交会出现「标了一半」的中间态
        return db.withTransaction {
            var total = 0
            paths.toList().chunked(QUERY_CHUNK).forEach { chunk ->
                total += db.fileMediaItemDao().setPrivate(sourceId, chunk, isPrivate)
            }
            total
        }
    }

    private fun FileSeen.toEntity(t: Long) = FileMediaItemEntity(
        sourceId = sourceId,
        path = path,
        // D7：本期只扫视频，音频文件根本不会走到这里
        kind = KIND_VIDEO,
        name = name,
        title = title,
        sortTitle = title.lowercase(),
        sizeBytes = sizeBytes,
        fileModifiedAt = fileModifiedAt,
        firstSeenAt = t,
        lastSeenAt = t,
        missingAt = null,
        libraryFolder = libraryFolder,
        durationMs = null,
        width = null,
        height = null,
        videoCodec = null,
        audioCodec = null,
        bitrate = null,
        thumbPath = null,
        probeState = ProbeState.NONE.name,
        probedAt = null,
        // 新发现的文件一律可见；要变私有两种途径：长按标记（setPrivate）或整个源为隐私源。
        // 重扫命中走 touch()，白名单里没有 isPrivate，所以已标记的不会被冲掉。
        isPrivate = false,
    )

    private fun FileMediaItemEntity.toModel() = FileEntry(
        sourceId = sourceId,
        path = path,
        name = name,
        title = title,
        sizeBytes = sizeBytes,
        fileModifiedAt = fileModifiedAt,
        firstSeenAt = firstSeenAt,
        libraryFolder = libraryFolder,
        durationMs = durationMs,
        width = width,
        height = height,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        bitrate = bitrate,
        probeState = runCatching { ProbeState.valueOf(probeState) }.getOrDefault(ProbeState.NONE),
        missingAt = missingAt,
        isPrivate = isPrivate,
    )

    private companion object {
        const val KIND_VIDEO = "VIDEO"
        const val KIND_FOLDER = "FOLDER"
        const val INSERT_CHUNK = 200
        const val QUERY_CHUNK = 500
    }
}

/** 明细行 → 领域模型。`kind` 列是字符串，脏值一律降级成 VIDEO（详情页最坏只是分区放错）。 */
private fun MediaStreamEntity.toStreamInfo() = MediaStreamInfo(
    kind = runCatching { StreamKind.valueOf(kind) }.getOrDefault(StreamKind.VIDEO),
    codec = codec,
    language = language,
    displayTitle = displayTitle,
    width = width,
    height = height,
    frameRate = frameRate,
    bitDepth = bitDepth,
    channels = channels,
    sampleRate = sampleRate,
    bitrate = bitrate,
    isExternal = isExternal,
    isDefault = isDefault,
)
