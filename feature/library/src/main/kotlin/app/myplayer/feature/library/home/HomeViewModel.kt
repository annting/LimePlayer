package app.myplayer.feature.library.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
class HomeViewModel(
    serverId: String?,
    private val servers: List<HomeServerOption>,
    data: HomeDataSource,
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
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

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
            runCatching { data.load(target) }
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = null,
                            serverName = snapshot.serverName.ifBlank { it.serverName },
                            continueWatchingList = snapshot.continueWatching,
                            libraries = snapshot.libraries,
                            recentlyAddedList = snapshot.recentlyAdded,
                        )
                    }
                }
                .onFailure { e ->
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
}
