package app.myplayer.source.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.FileCatalogStore
import app.myplayer.core.model.FileEntry
import app.myplayer.core.model.FileSeen
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.ItemQuery
import app.myplayer.core.model.LocalIgnoreStore
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
import app.myplayer.core.model.treeAliasOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/**
 * 本机视频源（`docs/12-本机视频播放方案.md`）。读手机自身存储的视频文件。
 *
 * ## 两条取文件通道（`docs/12` §3.1）
 * 由调用方决定走哪条，**同一时刻只用一条**（否则同名目录会被两棵树同时命中，归属二义）：
 * - [allFilesAccess] = false（默认）→ **SAF 多根**：用户逐棵授权的 tree URI（[treeUris]）；
 * - [allFilesAccess] = true → **全盘档**：直接用 `File` 扫 `/storage/emulated/0`，
 *   能覆盖 SAF 禁止选的 `Download` 根，扫描也快一个量级。
 *
 * 两档共用同一套路径语义（`<别名>/<树内相对路径>`，见 [treeAliasOf]），
 * 而全盘档的别名是空串 → `Movies/a.mp4` 在两档下 `mediaKey` 相同，**换档不丢进度**。
 *
 * ## 与 SMB 的关系
 * 同属「文件型源」，共用 `file_media_items` / `playback_progress` / `FileCatalogStore` /
 * `PlaybackStore` 一套底座；差异只在**访问方式**与**播放 URI**：
 * - SMB 走 smbj + 本地 HTTP 代理；本机直出 `content://`（SAF）或 `file://`（全盘），**无代理**。
 *
 * ## 层级映射
 * ```
 * 根（SAF 授权目录 / 外部存储根）
 *   ├─ 根下的视频        → 库「<根名>」/「本机根目录」
 *   └─ 顶层文件夹        → 库「<子目录名>」（递归确认含视频才出现）
 * ```
 *
 * ## 进度
 * 断点由播放层统一写 `PlaybackStore`（D1）；本类只读，[reportProgress] 为空实现。
 */
class LocalMediaSource(
    private val context: Context,
    treeUris: List<String>,
    override val serverId: String = DEFAULT_SERVER_ID,
    private val playback: PlaybackStore? = null,
    private val catalog: FileCatalogStore? = null,
    private val ignore: LocalIgnoreStore? = null,
    /** true = 走「全盘访问」档（`File` 直读），忽略 [treeUris]。 */
    val allFilesAccess: Boolean = false,
) : MediaSource {

    override val type: SourceType = SourceType.LOCAL

    /** 已授权的树 URI（UI 用来检查持久化授权是否仍有效，失效时引导重新授权）。 */
    val rootUris: List<String> = treeUris.filter { it.isNotBlank() }.distinct()

    /**
     * 当前生效的树：全盘档一棵（外部存储根），否则每棵授权子树一棵。
     *
     * 全盘档下若拿不到外部存储根（极少数 ROM），退回 SAF 根，保证不至于"整个 tab 空掉"。
     */
    private val roots: List<LocalTree> by lazy {
        if (allFilesAccess) {
            FileTree.external()?.let { listOf<LocalTree>(FileTree(it)) }
                ?: safRoots()
        } else {
            safRoots()
        }
    }

    private fun safRoots(): List<LocalTree> = rootUris.mapNotNull { uri ->
        runCatching { SafTree(context.contentResolver, Uri.parse(uri), treeAliasOf(uri)) }.getOrNull()
    }

    /** 别名越长越具体：用于「最长前缀优先」的归属判定。 */
    private val rootsBySpecificity: List<LocalTree> by lazy { roots.sortedByDescending { it.alias.length } }

    /** 路径归属：把合并路径拆成「命中哪棵树 + 树内相对路径」；空相对路径 = 该根自身。 */
    private class Located(val tree: LocalTree, val relPath: String)

    private fun locate(path: String): Located? {
        for (root in rootsBySpecificity) {
            val a = root.alias
            // 空前缀 = 卷根，兜底兜住任意路径（排在最后才轮到它）
            if (a.isEmpty()) return Located(root, path)
            if (path == a) return Located(root, "")
            if (path.startsWith("$a/")) return Located(root, path.removePrefix("$a/"))
        }
        return null
    }

    // ---------------------------------------------------------------- 库 / 条目

    override suspend fun getLibraries(): List<MediaLibrary> = withContext(Dispatchers.IO) {
        runCatching {
            val ignored = ignore?.ignoredFolders(serverId).orEmpty()
            val out = LinkedHashMap<String, MediaLibrary>()
            roots.forEach { tree -> collectLibraries(tree, tree.alias, "", ignored, 0, out) }
            android.util.Log.i(
                "LimeLocal",
                "扫描完成：roots=${roots.size}（allFiles=$allFilesAccess）库=${out.size} 忽略=${ignored.size}",
            )
            out.values.toList()
        }.onFailure {
            // 授权失效 / provider 异常时不要静默变「没有视频」——日志是唯一线索
            android.util.Log.w("LimeLocal", "扫描本机库失败（roots=${roots.size}）", it)
        }.getOrDefault(emptyList())
    }

    /**
     * 递归找出「**直接**含视频」的目录，作为库。
     *
     * 关键口径（2026-11-01 修复）：**卡片上的数量必须等于打开后看到的条目数**。
     * 之前是"递归计数 + 只列一层"，于是出现「显示 12 个视频，打开却是空的」——
     * 因为视频全在子目录里，而 `getItems` 只列直接子项。
     *
     * 规则：
     * - 当前目录有直接视频 → 它就是一个库（数量 = 直接视频数）；
     * - **无论本层有没有视频，都继续往下展开**（最多 [MAX_SCAN_DEPTH] 层），
     *   把每个"真正装视频"的子目录单独列成库（如 `DCIM/Camera` → 库名「Camera」）。
     *
     * 为什么不"本层有视频就停"：全盘档根目录常有 20+ 个散落视频，一停就再也进不去
     * `Movies` / `Download` 这些子目录了（2026-11-01 真机踩到）。多几张卡换取"所有视频都能进去"。
     */
    private fun collectLibraries(
        tree: LocalTree,
        alias: String,
        relPath: String,
        ignored: Set<String>,
        depth: Int,
        out: LinkedHashMap<String, MediaLibrary>,
    ) {
        if (depth > MAX_SCAN_DEPTH) return
        val children = tree.children(relPath)
        val videos = children.count { !it.isDirectory && it.name.isVideoFile() }
        val folder = joinRelative(alias, relPath)
        if (videos > 0 && folder !in ignored) {
            val id = localLibraryId(folder)
            val name = if (relPath.isBlank()) rootLibraryNameOf(alias) else relPath.substringAfterLast('/')
            out.putIfAbsent(id, MediaLibrary(id, name, videos, "folders"))
        }
        children.filter { it.isDirectory }.forEach { dir ->
            val child = joinRelative(relPath, dir.name)
            if (joinRelative(alias, child) in ignored) return@forEach
            collectLibraries(tree, alias, child, ignored, depth + 1, out)
        }
    }

    override suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto> = withContext(Dispatchers.IO) {
        runCatching {
            val folder = localFolderOf(query.libraryId) ?: ""
            val located = locate(folder) ?: return@runCatching PagedResult<MediaItemDto>(emptyList(), 0, 0)
            val videos = located.tree.children(located.relPath)
                .filter { !it.isDirectory && it.name.isVideoFile() }
            if (videos.isEmpty()) return@runCatching PagedResult<MediaItemDto>(emptyList(), 0, 0)
            val meta = registerAndLoad(videos, folder)
            val items = videos.map { node ->
                val rel = joinRelative(folder, node.name)
                node.toDto(rel, folder, meta.entries[rel], meta.progress[rel])
            }
            PagedResult(items = items, totalCount = items.size, startIndex = 0)
        }.getOrDefault(PagedResult<MediaItemDto>(emptyList(), 0, 0))
    }

    override suspend fun getItemDetail(id: String): MediaItemDto = withContext(Dispatchers.IO) {
        val path = decodeLocalId(id)
        val located = locate(path) ?: error("文件不在已授权目录内：$path")
        val node = located.tree.resolve(located.relPath) ?: error("文件不存在：$path")
        val folder = path.substringBeforeLast('/', "")
        val meta = registerAndLoad(listOf(node), folder, pathOverride = listOf(path))
        node.toDto(path, folder, meta.entries[path], meta.progress[path]).copy(id = id, path = path)
    }

    /** 登记到 `file_media_items` 并取回探测结果 / 断点（与 SMB 同构）。 */
    private suspend fun registerAndLoad(
        nodes: List<LocalNode>,
        folder: String,
        pathOverride: List<String>? = null,
    ): CachedMeta {
        val paths = pathOverride ?: nodes.map { joinRelative(folder, it.name) }
        catalog?.recordSeen(
            nodes.mapIndexed { i, node ->
                FileSeen(
                    sourceId = serverId,
                    path = paths[i],
                    name = node.name,
                    title = titleOf(node.name),
                    sizeBytes = node.sizeBytes,
                    fileModifiedAt = node.lastModified,
                    libraryFolder = folder.takeIf { it.isNotBlank() },
                )
            }
        )
        return CachedMeta(
            entries = catalog?.entriesOf(serverId, paths).orEmpty(),
            progress = playback?.progressOf(serverId, paths).orEmpty(),
        )
    }

    private class CachedMeta(
        val entries: Map<String, FileEntry>,
        val progress: Map<String, PlaybackProgress>,
    )

    private fun LocalNode.toDto(
        relPath: String,
        folder: String,
        cached: FileEntry?,
        prog: PlaybackProgress?,
    ) = MediaItemDto(
        id = encodeLocalId(relPath),
        serverId = serverId,
        sourceType = SourceType.LOCAL,
        title = titleOf(name),
        container = name.substringAfterLast('.', "").ifBlank { null },
        sizeBytes = sizeBytes,
        dateCreated = lastModified,
        path = relPath,
        libraryId = localLibraryId(folder),
        // 时长兜底顺序 = 探测值 → 本地断点里的时长（与 SMB 一致，保证未进详情也能画进度条）
        durationTicks = (cached?.durationMs ?: prog?.durationMs)
            ?.takeIf { it > 0L }
            ?.let { Ticks.ofMillis(it) }
            ?: Ticks.ZERO,
        width = cached?.width,
        height = cached?.height,
        videoCodec = cached?.videoCodec,
        audioCodecs = listOfNotNull(cached?.audioCodec),
        bitrate = cached?.bitrate,
        userData = UserData(
            isPlayed = prog?.completed == true,
            resumePositionTicks = Ticks.ofMillis(prog?.positionMs ?: 0L),
        ),
        hasPoster = false,
        canDelete = true,
    )

    // ---------------------------------------------------------------- 播放 / 图片

    override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource =
        // 本机文件没有海报；缩略图由 app 层 LocalThumbnailProvider 抽帧兜底
        ImageSource.None

    override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan {
        val path = item.path ?: decodeLocalId(item.id)
        val located = locate(path) ?: error("文件不在已授权目录内：$path")
        val node = located.tree.resolve(located.relPath) ?: error("文件不存在：$path")
        // 直出 content://（SAF）或 file://（全盘）：ExoPlayer 原生支持，不需要 SMB 那种本地 HTTP 代理
        return PlaybackPlan.Direct(
            uri = located.tree.uriOf(node).toString(),
            mediaSourceId = path,
        )
    }

    override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null

    override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) = Unit

    // ---------------------------------------------------------------- 探测（详情页）

    override suspend fun probeMedia(item: MediaItemDto): MediaProbeInfo? = withContext(Dispatchers.IO) {
        val path = item.path ?: decodeLocalId(item.id)
        val store = catalog
        if (store != null) {
            when (store.entryOf(serverId, path)?.probeState) {
                ProbeState.OK -> return@withContext store.probeOf(serverId, path)
                ProbeState.UNSUPPORTED, ProbeState.FAILED -> return@withContext null
                else -> Unit
            }
        }
        val located = locate(path) ?: return@withContext null
        val node = located.tree.resolve(located.relPath) ?: return@withContext null
        val retriever = MediaMetadataRetriever()
        val info = try {
            retriever.setDataSource(context, located.tree.uriOf(node))
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            MediaProbeInfo(durationTicks = Ticks.ofMillis(durationMs))
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
        store?.recordProbe(
            sourceId = serverId,
            path = path,
            info = info,
            state = if (info != null) ProbeState.OK else ProbeState.UNSUPPORTED,
        )
        info
    }

    // ---------------------------------------------------------------- 首页 / 续播

    override suspend fun getLatestItems(limit: Int): List<MediaItemDto> = withContext(Dispatchers.IO) {
        runCatching {
            val ignored = ignore?.let { runCatching { it.ignoredFolders(serverId) }.getOrNull() }.orEmpty()
            val all = scanTopLevelVideos(ignored).sortedByDescending { it.second.lastModified }.take(limit)
            if (all.isEmpty()) return@runCatching emptyList()
            val byFolder = all.groupBy { it.first }
            val entries = mutableMapOf<String, FileEntry>()
            val progress = mutableMapOf<String, PlaybackProgress>()
            byFolder.forEach { (folder, pairs) ->
                val meta = registerAndLoad(pairs.map { it.second }, folder)
                entries += meta.entries
                progress += meta.progress
            }
            all.map { (folder, node) ->
                val rel = joinRelative(folder, node.name)
                node.toDto(rel, folder, entries[rel], progress[rel])
            }
        }.getOrDefault(emptyList())
    }

    private fun scanTopLevelVideos(ignored: Set<String>): List<Pair<String, LocalNode>> {
        val out = ArrayList<Pair<String, LocalNode>>()
        roots.forEach { tree ->
            val alias = tree.alias
            if (alias in ignored) return@forEach
            val rootChildren = tree.children("")
            rootChildren.filter { !it.isDirectory && it.name.isVideoFile() }
                .forEach { out += alias to it }
            rootChildren.filter { it.isDirectory }.forEach { dir ->
                val folder = joinRelative(alias, dir.name)
                if (folder in ignored) return@forEach
                tree.children(folder)
                    .filter { !it.isDirectory && it.name.isVideoFile() }
                    .forEach { out += folder to it }
            }
        }
        return out
    }

    override suspend fun getResumeItems(limit: Int): List<MediaItemDto> = withContext(Dispatchers.IO) {
        val store = playback ?: return@withContext emptyList()
        val rows = runCatching { store.continueWatching(serverId, limit) }.getOrDefault(emptyList())
        // 只回当前根**能解释**的路径：移除根 / 换档后，旧 mediaKey 会变成孤儿，
        // 直接展示会点进去就崩（"文件不在已授权目录内"）。
        val live = rows.filter { locate(it.mediaKey) != null }
        if (live.isEmpty()) return@withContext emptyList()
        val entries = runCatching { catalog?.entriesOf(serverId, live.map { it.mediaKey }) }
            .getOrNull().orEmpty()
        live.map { resumeDto(it, entries[it.mediaKey]) }
    }

    private fun resumeDto(p: PlaybackProgress, entry: FileEntry?) = MediaItemDto(
        id = encodeLocalId(p.mediaKey),
        serverId = serverId,
        sourceType = SourceType.LOCAL,
        title = entry?.title ?: titleOf(p.mediaKey.substringAfterLast('/')),
        container = entry?.name?.substringAfterLast('.', "")?.ifBlank { null },
        sizeBytes = entry?.sizeBytes,
        dateCreated = entry?.fileModifiedAt,
        path = p.mediaKey,
        libraryId = localLibraryId(p.mediaKey.substringBeforeLast('/', "")),
        durationTicks = (entry?.durationMs ?: p.durationMs).takeIf { it > 0L }
            ?.let { Ticks.ofMillis(it) } ?: Ticks.ZERO,
        width = entry?.width,
        height = entry?.height,
        videoCodec = entry?.videoCodec,
        audioCodecs = listOfNotNull(entry?.audioCodec),
        bitrate = entry?.bitrate,
        userData = UserData(isPlayed = p.completed, resumePositionTicks = Ticks.ofMillis(p.positionMs)),
        hasPoster = false,
    )

    // ---------------------------------------------------------------- 标记 / 删除 / 映射

    override val canMarkPlayed: Boolean get() = playback != null

    override suspend fun markPlayed(item: MediaItemDto, played: Boolean): UserData =
        withContext(Dispatchers.IO) {
            val store = playback ?: throw UnsupportedOperationException("本地进度库未就绪")
            val path = item.path ?: decodeLocalId(item.id)
            if (played) {
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

    override suspend fun deleteItem(item: MediaItemDto): Boolean = withContext(Dispatchers.IO) {
        val path = item.path ?: decodeLocalId(item.id)
        val located = locate(path) ?: return@withContext false
        val node = located.tree.resolve(located.relPath) ?: return@withContext false
        located.tree.delete(node).also { ok ->
            if (ok) playback?.clear(serverId, path)
        }
    }

    override fun idForPath(path: String): String? = encodeLocalId(path)

    companion object {
        const val DEFAULT_SERVER_ID = "local"

        /** 「含视频」探测的最大递归深度：再深的目录不追（避免超大目录树首扫卡住）。 */
        private const val MAX_SCAN_DEPTH = 3
    }
}
