package app.myplayer.source.smb

import app.myplayer.core.model.AdjacentEpisodes
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.FileCatalogStore
import app.myplayer.core.model.FileEntry
import app.myplayer.core.model.FileSeen
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.ItemQuery
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary
import app.myplayer.core.model.MediaProbeInfo
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.PagedResult
import app.myplayer.core.model.PlayState
import app.myplayer.core.model.PlaybackPlan
import app.myplayer.core.model.PlaybackProgress
import app.myplayer.core.model.PlaybackStore
import app.myplayer.core.model.ProbeState
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.TrickplaySpec
import app.myplayer.core.model.UserData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/**
 * SMB 共享目录源（`\\host\share`）。
 *
 * ## 层级映射
 * ```
 * \\192.168.31.15\video            → 一个 SMB 服务器（ServerConfig）
 *   ├─ Movies/                      → 一个「库」（首页「我的文件夹」卡片）
 *   │    ├─ 沙丘.mkv                → 一个条目（点开即播放）
 *   │    └─ 剧集/                    → 子目录：详情页里当作「文件」过滤掉（v1 只扫一层）
 *   └─ TV/                          → 另一个「库」
 * ```
 * 只扫一层是刻意的：递归扫整个共享在机械盘上可能要几十秒，而媒体目录通常是
 * 「共享 → 分类目录 → 视频文件」两层结构。
 *
 * ## 播放
 * ExoPlayer 读不了 `smb://`，全部走 [SmbStreamServer] 的 `http://127.0.0.1:<port>/<token>` 桥接，
 * 支持 Range（拖进度条只读新区间）。
 *
 * ## 不支持的能力
 * 海报 / 时长 / 转码：SMB 没有元数据服务。详情页靠占位图 + 文件属性 + 探测到的技术信息展示。
 *
 * ## 本地进度与技术信息（2026-10-27 起）
 * SMB 没有服务端可以存进度，所以断点与探测结果落**本地库**，但本模块**不依赖 Room**：
 * - [progress] 是 `:core:model` 的 `PlaybackStore` 接口（写方是播放层，本类只读）；
 * - [catalog] 是 `FileCatalogStore` 接口：列目录时登记条目（`firstSeenAt` 只写一次），
 *   探测结果写库，第二次进详情**不再读文件头**。
 *
 * 两者都可为 null（单测 / 预览），此时退化成「每次现场探测 + 无断点」的老行为。
 */
class SmbMediaSource(
    private val config: SmbConfig,
    override val serverId: String,
    private val progress: PlaybackStore? = null,
    private val catalog: FileCatalogStore? = null,
) : MediaSource {

    override val type: SourceType = SourceType.SMB

    private val client: SmbClient by lazy { SmbClients.of(config) }

    /** 第一个用到播放时才启动（避免只为列个目录就占一个端口）。 */
    private val streamServer: SmbStreamServer by lazy {
        SmbStreamServer { path -> buildTarget(path) }.also { it.start() }
    }

    private fun buildTarget(path: String): SmbStreamServer.StreamTarget? {
        val meta = runCatching { client.stat(path) }.getOrNull() ?: return null
        return SmbStreamServer.StreamTarget(
            client = client,
            path = path,
            sizeBytes = meta.sizeBytes,
            mimeType = mimeTypeOf(path),
        )
    }

    /**
     * 条目 id 必须**放进导航路由**（`detail/{itemId}`、`player/{itemId}`），
     * 而 SMB 的真实路径含 `/`、空格、括号 —— 直接当 id 用会让 route 变成
     * `detail/FigureRobics/FigureRobics (1).avi`，导航图里找不到 → 点一次崩一次
     * （2026-10-25 一加实测）。所以对外用 URL 安全 Base64，真实路径只放在 [MediaItemDto.path]。
     */
    internal fun encodeId(path: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(path.toByteArray(Charsets.UTF_8))

    internal fun decodeId(id: String): String =
        runCatching { String(java.util.Base64.getUrlDecoder().decode(id), Charsets.UTF_8) }
            .getOrDefault(id)

    /** 库 id ↔ 相对路径：`smb:Movies` ↔ `Movies`。 */
    private fun libraryIdOf(folder: String) = "smb:$folder"
    private fun pathOfLibrary(libraryId: String?) =
        libraryId?.removePrefix("smb:")?.takeIf { it.isNotBlank() && it != libraryId }

    override suspend fun getLibraries(): List<MediaLibrary> = withContext(Dispatchers.IO) {
        client.list("")
            .filter { it.isDirectory }
            .map { entry ->
                MediaLibrary(
                    id = libraryIdOf(entry.path),
                    name = entry.name,
                    itemCount = runCatching {
                        client.list(entry.path).count { !it.isDirectory && it.name.isVideoFile() }
                    }.getOrNull(),
                    collectionType = "folders",
                )
            }
    }

    override suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto> = withContext(Dispatchers.IO) {
        val folder = pathOfLibrary(query.libraryId) ?: ""
        val entries = runCatching { client.list(folder) }.getOrDefault(emptyList())
            .filter { !it.isDirectory && it.name.isVideoFile() }
        val meta = registerAndLoad(entries, folder)
        val items = entries.map { it.toDto(folder, meta.entries[it.path], meta.progress[it.path]) }
        PagedResult(items = items, totalCount = items.size, startIndex = 0)
    }

    /**
     * 把「这次列到的文件」登记进库，并一次性取回已入库的探测结果与断点。
     *
     * 为什么在**列目录**时登记：库页 / 首页每次都会列一遍，这是唯一稳定覆盖全库的时机；
     * 而 `firstSeenAt` 只在首次插入时写（`RoomFileCatalog` 用 `INSERT OR IGNORE`），
     * 所以重扫不会把「昨日新增」抹成今天。
     */
    private suspend fun registerAndLoad(entries: List<SmbEntry>, folder: String): CachedMeta {
        val cat = catalog
        val prog = progress
        if (cat == null && prog == null) return CachedMeta.EMPTY
        val paths = entries.map { it.path }
        if (cat != null) {
            cat.recordSeen(
                entries.map {
                    FileSeen(
                        sourceId = serverId,
                        path = it.path,
                        name = it.name,
                        title = it.name.substringBeforeLast('.'),
                        sizeBytes = it.sizeBytes,
                        fileModifiedAt = it.lastModifiedMillis,
                        libraryFolder = folder.takeIf { f -> f.isNotBlank() },
                    )
                }
            )
        }
        return CachedMeta(
            entries = cat?.entriesOf(serverId, paths).orEmpty(),
            progress = prog?.progressOf(serverId, paths).orEmpty(),
        )
    }

    private class CachedMeta(
        val entries: Map<String, FileEntry>,
        val progress: Map<String, PlaybackProgress>,
    ) {
        companion object {
            val EMPTY = CachedMeta(emptyMap(), emptyMap())
        }
    }

    /**
     * 条目 DTO。三份数据合成：文件属性（SMB stat）+ 入库的探测结果（时长/分辨率/编码）
     * + 本地断点（`userData`）。后两者缺失时退化成 0/空，与老行为一致。
     */
    private fun SmbEntry.toDto(
        folder: String,
        cached: FileEntry? = null,
        prog: PlaybackProgress? = null,
    ) = MediaItemDto(
        id = encodeId(path),
        serverId = serverId,
        sourceType = SourceType.SMB,
        title = name.substringBeforeLast('.'),
        container = name.substringAfterLast('.', ""),
        sizeBytes = sizeBytes,
        dateCreated = lastModifiedMillis,
        path = path,
        libraryId = folder.takeIf { it.isNotBlank() }?.let(::libraryIdOf),
        // 入库的探测结果：列表页的进度条（resumeFraction）依赖时长，不填就画不出来（F5）
        durationTicks = cached?.durationMs?.let { Ticks.ofMillis(it) } ?: Ticks.ZERO,
        width = cached?.width,
        height = cached?.height,
        videoCodec = cached?.videoCodec,
        audioCodecs = listOfNotNull(cached?.audioCodec),
        bitrate = cached?.bitrate,
        // 本地断点（SMB 无服务端，这里就是权威值）
        userData = UserData(
            isPlayed = prog?.completed == true,
            resumePositionTicks = Ticks.ofMillis(prog?.positionMs ?: 0L),
        ),
        hasPoster = false,
    )

    override suspend fun getItemDetail(id: String): MediaItemDto = withContext(Dispatchers.IO) {
        val path = decodeId(id)
        val entry = client.stat(path) ?: error("文件不存在：$path")
        val folder = path.substringBeforeLast('/', "")
        val meta = registerAndLoad(listOf(entry.copy(path = path)), folder)
        entry.toDto(folder, meta.entries[path], meta.progress[path]).copy(id = id, path = path)
    }

    override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource =
        // SMB 没有海报服务：一律 None，UI 走占位图（不要在这里请求缩略图，会变成每个格子一次 SMB 读）
        ImageSource.None

    /**
     * 现场探测轨道信息（详情页「视频信息 / 音频信息 / 字幕信息」）。
     *
     * 走的是播放同一条本机 HTTP 桥：`MediaExtractor` 不认 `smb://`，但认 `http://127.0.0.1` + Range。
     * 解析失败（`MediaExtractor` 不支持该容器）返回 null，详情页保持「未下发技术信息」的弱化说明。
     */
    override suspend fun probeMedia(item: MediaItemDto): MediaProbeInfo? = withContext(Dispatchers.IO) {
        val path = item.path ?: decodeId(item.id)

        // ① 先看库 —— 「取一次存库」的核心：探过就直接复用，第二次进详情 0 次 MediaExtractor
        val store = catalog
        if (store != null) {
            when (store.entryOf(serverId, path)?.probeState) {
                ProbeState.OK -> return@withContext store.probeOf(serverId, path)
                // 探过了但容器读不出来（AVI 这类）：不再重试，详情页保持「未探测」的弱化说明
                ProbeState.UNSUPPORTED, ProbeState.FAILED -> return@withContext null
                else -> Unit
            }
        }

        // ② 内存负缓存兜底（没有 catalog 时，同一会话内反复进出详情不重复读文件头）
        synchronized(probeCache) { probeCache[path] }?.let { return@withContext it.asInfoOrNull() }

        streamServer.start()                     // 幂等；只为探测也会占一个端口，可接受
        val probed = probeMediaInfo(streamServer.urlFor(path))
        synchronized(probeCache) {
            probeCache[path] = probed ?: PROBE_MISS
            if (probeCache.size > PROBE_CACHE_LIMIT) {
                probeCache.entries.iterator().let { it.next(); it.remove() }   // 简单 FIFO 淘汰
            }
        }
        // ③ 落库：成功与「探不出来」都写 —— 后者避免每次进详情都去试一遍注定失败的容器
        store?.recordProbe(
            sourceId = serverId,
            path = path,
            info = probed,
            state = if (probed != null) ProbeState.OK else ProbeState.UNSUPPORTED,
        )
        probed
    }

    /** 探测结果缓存：`null` 用哨兵对象表示（ConcurrentHashMap / LinkedHashMap 都不接受 null 值）。 */
    private val probeCache = LinkedHashMap<String, Any>(16, 0.75f, true)

    private fun Any.asInfoOrNull(): MediaProbeInfo? = this as? MediaProbeInfo

    override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan {
        // F4：从 Room 直接拿到的条目 path 为 null，不能把 Base64 id 当路径去 stat
        val path = item.path ?: decodeId(item.id)
        streamServer.start()                    // 幂等；端口在首次播放时才占
        return PlaybackPlan.Direct(uri = streamServer.urlFor(path), mediaSourceId = path)
    }

    override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null

    /**
     * SMB 没有服务端，远端上报无意义 —— 本地断点由播放层写 `PlaybackStore`（D1）。
     * 保留空实现而不是删掉接口：`MediaSource` 契约不变，在线源继续用它上报服务端。
     */
    override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) = Unit

    override suspend fun getLatestItems(limit: Int): List<MediaItemDto> = withContext(Dispatchers.IO) {
        runCatching {
            val all = client.list("")
                .filter { it.isDirectory }
                .flatMap { dir -> client.list(dir.path).filter { !it.isDirectory && it.name.isVideoFile() } }
                .sortedByDescending { it.lastModifiedMillis }
                .take(limit)
            // 首页是「建库」的主力入口之一：顺手把条目登记进 file_media_items
            val entries = mutableMapOf<String, FileEntry>()
            val prog = mutableMapOf<String, PlaybackProgress>()
            all.groupBy { it.path.substringBeforeLast('/', "") }.forEach { (folder, group) ->
                val meta = registerAndLoad(group, folder)
                entries += meta.entries
                prog += meta.progress
            }
            all.map { it.toDto(it.path.substringBeforeLast('/', ""), entries[it.path], prog[it.path]) }
        }.getOrDefault(emptyList())
    }

    /**
     * 首页「继续观看」：**从本地断点表组装**（F6）。
     *
     * 老实现直接返回空，导致首页这个模块被 `hasContent` 判据隐藏 —— 播到一半退出后
     * 首页什么都没留下。现在按 `lastPlayedAt` 倒序取出，标题/时长从 `file_media_items` 补。
     */
    override suspend fun getResumeItems(limit: Int): List<MediaItemDto> = withContext(Dispatchers.IO) {
        val store = progress ?: return@withContext emptyList()
        val rows = runCatching { store.continueWatching(serverId, limit) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return@withContext emptyList()
        val entries = runCatching { catalog?.entriesOf(serverId, rows.map { it.mediaKey }) }
            .getOrNull().orEmpty()
        rows.map { resumeDto(it, entries[it.mediaKey]) }
    }

    /** 「继续观看」条目：库里只有 key，标题/时长靠库存行补；库存缺了就按文件名兜底。 */
    private fun resumeDto(p: PlaybackProgress, entry: FileEntry?) = MediaItemDto(
        id = encodeId(p.mediaKey),
        serverId = serverId,
        sourceType = SourceType.SMB,
        title = entry?.title ?: p.mediaKey.substringAfterLast('/').substringBeforeLast('.'),
        container = entry?.name?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() },
        sizeBytes = entry?.sizeBytes,
        dateCreated = entry?.fileModifiedAt,
        durationTicks = (entry?.durationMs ?: p.durationMs).takeIf { it > 0 }
            ?.let { Ticks.ofMillis(it) } ?: Ticks.ZERO,
        width = entry?.width,
        height = entry?.height,
        videoCodec = entry?.videoCodec,
        audioCodecs = listOfNotNull(entry?.audioCodec),
        bitrate = entry?.bitrate,
        path = p.mediaKey,
        libraryId = p.mediaKey.substringBeforeLast('/', "").takeIf { it.isNotBlank() }?.let(::libraryIdOf),
        userData = UserData(isPlayed = p.completed, resumePositionTicks = Ticks.ofMillis(p.positionMs)),
        hasPoster = false,
    )

    override suspend fun getAdjacentEpisodes(item: MediaItemDto): AdjacentEpisodes = AdjacentEpisodes()

    /**
     * 观看历史页「点一条记录 → 进详情」的路径→id 映射（`MediaSource.idForPath`）。
     * 与列表项的 id 完全同源（[encodeId]），详情页 `getItemDetail` 无感。
     */
    override fun idForPath(path: String): String? = encodeId(path)

    private companion object {
        /** 「探测过但读不出」的哨兵：负缓存，避免每次进详情都去试一遍不支持的容器。 */
        val PROBE_MISS = Any()

        /** 探测缓存上限（只放几百字节的元数据，200 条足够覆盖一个文件夹的来回浏览）。 */
        const val PROBE_CACHE_LIMIT = 200
    }
}
