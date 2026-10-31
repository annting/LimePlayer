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
import app.myplayer.core.model.HidePrivate
import app.myplayer.core.model.PlaybackStore
import app.myplayer.core.model.PrivacyVisibilityPolicy
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
    /**
     * 档二可见性（`docs/隐私模式方案.md` §六）：**策略在实现方取值**，调用方拿不到开关。
     * 默认 [HidePrivate] —— 漏装配时「少显示」而不是「漏数据」。
     */
    private val privacy: PrivacyVisibilityPolicy = HidePrivate,
) : MediaSource {

    override val type: SourceType = SourceType.SMB

    private val client: SmbClient by lazy { SmbClients.of(config) }

    /** 第一个用到播放时才启动（避免只为列个目录就占一个端口）。 */
    private val streamServerDelegate = lazy {
        SmbStreamServer { path -> buildTarget(path) }.also { it.start() }
    }
    private val streamServer: SmbStreamServer get() = streamServerDelegate.value

    /**
     * 释放本机流代理端口（P1-5）：`streamServer.stop()` 此前从不被调用，增删 SMB 服务器会
     * 累积 listen 端口 + daemon 线程。只在确实启动过（播过）时才 stop，避免 close 反而拉起 server。
     */
    fun close() {
        if (streamServerDelegate.isInitialized()) streamServerDelegate.value.stop()
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
        val entries = client.list("").filter { it.isDirectory }
        // 档二（目录级递归）：普通模式下把标了隐私的文件夹整个挡掉（首页「我的文件夹」卡片同源）
        val hidden = if (!privacy.includePrivate()) {
            runCatching { catalog?.privatePaths(serverId, entries.map { it.path }) }.getOrNull().orEmpty()
        } else {
            emptySet()
        }
        entries.filter { it.path !in hidden }.map { entry ->
            val children = runCatching { client.list(entry.path) }.getOrDefault(emptyList())
            // 计数也要递归排除：私有文件夹下的文件不能再被数进「N 个视频」
            val deepHidden = if (!privacy.includePrivate() && catalog != null && children.isNotEmpty()) {
                runCatching { catalog.privatePaths(serverId, children.map { it.path }) }.getOrNull().orEmpty()
            } else {
                emptySet()
            }
            MediaLibrary(
                id = libraryIdOf(entry.path),
                name = entry.name,
                itemCount = children
                    .filter { it.path !in deepHidden }
                    .count { !it.isDirectory && it.name.isVideoFile() },
                collectionType = "folders",
            )
        }
    }

    override suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto> = withContext(Dispatchers.IO) {
        val folder = pathOfLibrary(query.libraryId) ?: ""
        val entries = runCatching { client.list(folder) }.getOrDefault(emptyList())
            .filter { !it.isDirectory && it.name.isVideoFile() }
        val meta = registerAndLoad(entries, folder)
        // 档二：普通模式下把标了隐私的文件挡在目录列表外（否则「标了隐私还在文件夹里躺着」）
        val hidden = hiddenPaths(entries.map { it.path })
        val items = entries
            .filter { it.path !in hidden }
            .map { it.toDto(folder, meta.entries[it.path], meta.progress[it.path]) }
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
        // 时长兜底顺序 = 探测值 → **本地断点里的时长**：
        // `probeMedia` 只在详情页触发，直接在网格里点播放、从不进详情页的片子永远没有探测时长，
        // 于是 `resumeFraction()` 恒为 0、媒体库看不到进度条（2026-10-31 定位）。
        // 断点里的 durationMs 是播放层写的（起播时 ExoPlayer 已知总长），足够算比例。
        durationTicks = (cached?.durationMs ?: prog?.durationMs)
            ?.takeIf { it > 0L }
            ?.let { Ticks.ofMillis(it) }
            ?: Ticks.ZERO,
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
        // SMB 是文件本体：详情页/首页的删除走物理删除（MediaSource.deleteItem）
        canDelete = true,
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
            val hidden = hiddenPaths(all.map { it.path })
            all.filter { it.path !in hidden }
                .map { it.toDto(it.path.substringBeforeLast('/', ""), entries[it.path], prog[it.path]) }
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
        val hidden = hiddenPaths(rows.map { it.mediaKey })
        rows.filter { it.mediaKey !in hidden }.map { resumeDto(it, entries[it.mediaKey]) }
    }

    /**
     * 档二：这批路径里在当前模式下**要隐藏**的那些。
     *
     * 用 [FileCatalogStore.privatePaths] 而不是 `entriesOf` 里的 `isPrivate`：
     * 后者按可见性策略过滤，普通模式下私有行根本查不出来，分不清「没入库」和「是隐私」。
     * 隐私模式直接返回空集（全量可见），连这次查询都省掉。
     */
    private suspend fun hiddenPaths(paths: List<String>): Set<String> {
        if (privacy.includePrivate() || paths.isEmpty()) return emptySet()
        return runCatching { catalog?.privatePaths(serverId, paths) }.getOrNull().orEmpty()
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
     * 物理删除（`docs/隐私模式方案.md` §三）：SMB 没有服务端删除接口，
     * 直接删共享上的文件（不经过回收站，调用方必须先确认过）。
     *
     * 成功后顺手清掉本地断点 —— 文件都没了，继续观看里不能再挂着它。
     * 库存行（`file_media_items`）不清：下轮扫描 markMissing 自然标记（本期只标不删）。
     */
    override suspend fun deleteItem(item: MediaItemDto): Boolean = withContext(Dispatchers.IO) {
        val path = item.path ?: decodeId(item.id)
        runCatching { client.delete(path) }
            .onSuccess { progress?.clear(serverId, path) }
            .isSuccess
    }

    /**
     * 「标记已播放」（2026-10-31）。SMB 没有服务端，状态写本地断点库 [progress]：
     *
     * - 标记已看 = 写一条 `completed = true` 的断点（按不变量位置清零，`playCount` +1）；
     * - 取消已看 = 删掉断点，条目回到「未看过」（列表的 `isPlayed` 与进度条一起消失）。
     *
     * 能力位跟着 [progress] 走：没装配库（单测 / 预览）时保持默认 false，详情页不出对勾。
     */
    override val canMarkPlayed: Boolean get() = progress != null

    override suspend fun markPlayed(item: MediaItemDto, played: Boolean): UserData =
        withContext(Dispatchers.IO) {
            val store = progress ?: throw UnsupportedOperationException("本地进度库未就绪")
            val path = item.path ?: decodeId(item.id)
            if (played) {
                // 时长优先用条目自带的（详情页探测过就有），否则沿用库里旧值 ——
                // 传 0 会把已有时长覆盖掉，之后这张卡的进度条就再也算不出来了。
                val known = store.progressOf(serverId, path)?.durationMs ?: 0L
                val durationMs = item.durationTicks.millis.takeIf { it > 0L } ?: known
                store.recordPosition(
                    sourceId = serverId,
                    mediaKey = path,
                    positionMs = 0L,
                    durationMs = durationMs,
                    completed = true,
                )
                UserData(isPlayed = true, resumePositionTicks = Ticks.ZERO)
            } else {
                store.clear(serverId, path)
                UserData(isPlayed = false, resumePositionTicks = Ticks.ZERO)
            }
        }

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
