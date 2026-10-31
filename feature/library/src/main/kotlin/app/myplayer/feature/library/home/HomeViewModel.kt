package app.myplayer.feature.library.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.datastore.HomeModuleSettings
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 首页状态机。
 *
 * 四个入口全部收敛到 [load]：
 * - `init` → 首屏（`isLoading` = true，页面显示骨架屏）
 * - [refresh] → 下拉刷新（`isRefreshing` = true，**内容保留**，只有顶部转圈）
 * - [selectServer] → 切换来源（写 `activeServerId` 后上层重建，本类只负责即时反馈）
 * - [rebind] → 数据源实例变了（重新登录 / 改端点后 `source` 重建），重新走首屏
 *
 * 失败统一落 [HomeUiState.error]，页面据此进全屏空态 2；重试就是再调一次 [refresh]。
 *
 * @param servers 顶部「切换来源」候选 —— 来自 `ServerConfigStore`（真实配置），
 *   不再是数据源的一部分：一台 [MediaSource] 只对应一台服务器，两者职责不同。
 * @param data 取数实现：[ServerHomeDataSource]（真机）/ [MockHomeDataSource]（测试与预览）。
 * @param homeSettings 首页设置的读写入口（与设置页共用同一份 DataStore 存储）。
 */
class HomeViewModel(
    private val serverId: String?,
    private val servers: List<HomeServerOption>,
    data: HomeDataSource,
    homeSettings: HomeSettingsRepository,
    /**
     * 档二库存（2026-10-29）：首页卡片「设为 / 取消隐私」与左上角标志的数据源。
     * null = 未接（Emby 形态 / 预览 / 单测）→ 卡片不出三点菜单。
     */
    private val catalog: app.myplayer.core.model.FileCatalogStore? = null,
) : ViewModel() {

    private var data: HomeDataSource = data

    private val _state = MutableStateFlow(
        HomeUiState(
            isLoading = true,
            servers = servers,
            selectedServerId = serverId ?: servers.firstOrNull()?.id,
            serverName = servers.firstOrNull { it.id == serverId }?.name,
        )
    )
    // 当前源形态流：给 repository 用来在 Emby / SMB 两份设置之间自动切换
    private val sourceTypeFlow = _state.map { it.sourceType }.distinctUntilChanged()

    /**
     * 对外状态 = **加载流程 ⊕ 首页设置**。
     *
     * 响应式联动的核心：`_state` 只表达「取数发生了什么」，设置另成一条流 ——
     * `combine` 把两者合并成新的 [HomeUiState]。设置页写 DataStore → `settingsStore.settings`
     * 立刻发新值 → combine 重算 → 收集方（`collectAsStateWithLifecycle`）就地重组。
     * **整个过程与「返回首页」这个动作无关**，所以不需要刷新。
     */
    val state: StateFlow<HomeUiState> =
        combine(_state, homeSettings.observe(sourceTypeFlow)) { snap, settings ->
            snap.copy(
                settings = settings,
                // 最近添加在 VM 层做**物理过滤**（按天 + 按量），UI 只管渲染
                recentlyAddedList = applyRecentlyAddedWindow(snap.recentlyAddedList, settings),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, _state.value)

    private var loadJob: Job? = null

    init {
        load(initial = true)
    }

    /** 下拉刷新：不清空内容，只标记 [HomeUiState.isRefreshing]。 */
    fun refresh() = load(initial = false)

    /** 顶部切换来源。同一个服务器不重复请求。 */
    fun selectServer(id: String) {
        if (id == _state.value.selectedServerId) return
        _state.update {
            it.copy(
                selectedServerId = id,
                serverName = servers.firstOrNull { s -> s.id == id }?.name,
                error = null,
                // 换源等于换一屏内容：走骨架屏，避免旧服务器的卡片闪一下再被替换
                isLoading = true,
            )
        }
        load(initial = true)
    }

    /**
     * 页面送来了一个新的数据源包装。
     *
     * **判据是 [HomeDataSource.sourceKey]，不是 wrapper 的实例相等**：
     * 首页 tab 切走时 `HomeDestination` 退出组合、`remember` 槽位销毁，切回来
     * `ServerHomeDataSource(source, name)` 会被重新构造 —— 底层 `source` 明明没变，
     * 按实例相等判定就会误判成「换源」→ 置 `isLoading` → 整页塌成骨架屏再重载一遍
     * （BUG-2026-002）。
     *
     * 但新 wrapper 本身还是要换进来：它可能带着最新的服务器名（改了名字的场景）。
     */
    fun rebind(data: HomeDataSource) {
        val changed = data.sourceKey !== this.data.sourceKey
        this.data = data
        if (changed) load(initial = true)
    }

    /** 卡片的图片解析（与 `MediaSource.image()` 同签名，页面不关心具体源）。 */
    suspend fun image(item: MediaItemDto, kind: ImageKind): ImageSource = data.image(item, kind)

    private fun load(initial: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val target = _state.value.selectedServerId ?: return@launch
            _state.update {
                if (initial) it.copy(isLoading = true, error = null) else it.copy(isRefreshing = true, error = null)
            }
            try {
                val snapshot = data.load(target)
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = null,
                        serverName = snapshot.serverName.ifBlank { it.serverName },
                        sourceType = snapshot.sourceType,
                        continueWatchingList = snapshot.continueWatching,
                        myLibrariesOrFolders = snapshot.myLibrariesOrFolders,
                        dynamicSections = snapshot.dynamicSections,
                        recentlyAddedList = snapshot.recentlyAdded,
                        privateKeys = privateKeysOf(snapshot),
                    )
                }
            } catch (c: CancellationException) {
                // **取消不是失败**。换源 / 退出隐私重拉都会 `loadJob.cancel()`，
                // 用 `runCatching` 会把 CancellationException 一并写成失败态，
                // 界面就出现「无法连接到服务器 · StandaloneCoroutine was cancelled」
                // （2026-10-29 真机：退出隐私后首页直接红字报错）。状态交给接替的那次 load。
                throw c
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = e.message?.takeIf(String::isNotBlank) ?: "网络异常，请检查与服务器的连接",
                    )
                }
            }
        }
    }

    /**
     * 首页卡片的三点菜单（2026-10-29）：把一张卡设为 / 取消隐私（档二）。
     *
     * - 文件夹卡：[key] 是 `smb:<相对路径>` → 写目录标记，递归盖住整棵子树；
     * - 视频卡：[key] 是真实文件路径 → 写文件标记。
     * 当前态从库存现查（不要信 `privateKeys` 快照：连续点两张卡时第二张的快照已过期）。
     * 成功后 [refresh]：普通模式下「设为隐私」的卡当场消失，这就是反馈。
     */
    fun toggleCardPrivate(key: String, isFolder: Boolean) {
        val store = catalog ?: return
        val sid = serverId ?: return
        viewModelScope.launch {
            runCatching {
                if (isFolder) {
                    val path = key.removePrefix(FOLDER_ID_PREFIX)
                    val isPrivate = path in store.privateFolders(sid)
                    store.setFolderPrivate(sid, listOf(path), !isPrivate)
                } else {
                    val isPrivate = store.entryOf(sid, key)?.isPrivate == true
                    store.setPrivate(sid, listOf(key), !isPrivate)
                }
            }.onSuccess { refresh() }
        }
    }

    /**
     * 本屏所有「被标了隐私」的 key：文件真实路径 + 文件夹相对路径（`smb:` 前缀剥掉）。
     * 只在 SMB/LOCAL 形态下查（Emby 没有档二，卡片也不出菜单）。
     */
    private suspend fun privateKeysOf(snapshot: HomeSnapshot): Set<String> {
        val store = catalog ?: return emptySet()
        val sid = serverId ?: return emptySet()
        if (snapshot.sourceType != HomeSourceType.SMB) return emptySet()
        val keys = buildList {
            snapshot.continueWatching.forEach { it.path?.let(::add) }
            snapshot.recentlyAdded.forEach { it.path?.let(::add) }
            snapshot.dynamicSections.forEach { section -> section.items.forEach { it.path?.let(::add) } }
            // SMB 形态下「我的文件夹」全部是文件夹卡（isFolder 在数据源里未显式置 true，
            // 渲染时靠 folderMode 兜底）—— 这里直接按库 id 剥前缀当目录路径收集
            snapshot.myLibrariesOrFolders.forEach { add(it.id.removePrefix(FOLDER_ID_PREFIX)) }
        }
        if (keys.isEmpty()) return emptySet()
        return runCatching { store.privatePaths(sid, keys) }.getOrDefault(emptySet())
    }

    private companion object {
        /** 文件夹卡 id 前缀（`ServerHomeDataSource` 用库 id 直接当卡 id）。 */
        const val FOLDER_ID_PREFIX = "smb:"
    }
}

/** 一天的毫秒数（最近添加的「按天过滤」窗口单位）。 */
internal const val DAY_MS: Long = 24L * 60L * 60L * 1000L

/**
 * 最近添加的**物理过滤**（`docs/03` §7 首页设置 · 第四步）：
 *
 * 1. **按天**：`dateCreated` 距今不超过 `settings.recentlyAddedDays` 天的条目保留；
 *    **`dateCreated` 为 null 的条目不参与按天判定，但不丢弃** —— 见下方注释；
 * 2. **按量**：按时间倒序（时间未知的排最后）后 `take(settings.recentlyAddedLimit)`。
 *
 * 纯函数：`nowMs` 由调用方注入，测试不依赖真实时钟。
 *
 * ## 为什么 null 不再直接滤掉（2026-10-24 修正）
 * 上一版口径是「无法判定时间就丢掉」。但有些源**根本没有入库时间**：飞牛的
 * `item/list` 只保证 `create_time DESC` 排序、下发的条目没有 `create_time` 字段映射到
 * `dateCreated`；SMB 老缓存也可能缺。一刀切的结果是「最近添加」整块消失 ——
 * 用户只开这一个模块时首页直接空白（本次 bug）。这类条目仍受 `recentlyAddedLimit` 约束，
 * 只是无法按天裁；有时间的条目照旧严格按天过滤。
 */
internal fun applyRecentlyAddedWindow(
    items: List<MediaItemDto>,
    settings: HomeModuleSettings,
    nowMs: Long = System.currentTimeMillis(),
): List<MediaItemDto> {
    val oldest = nowMs - settings.recentlyAddedDays.coerceAtLeast(0) * DAY_MS
    return items
        .filter {
            val created = it.dateCreated
            created == null || created >= oldest
        }
        .sortedByDescending { it.dateCreated ?: Long.MIN_VALUE }
        .take(settings.recentlyAddedLimit.coerceAtLeast(0))
}
