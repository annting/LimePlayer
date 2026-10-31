package app.myplayer.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.UserData
import app.myplayer.core.model.groupBySeason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 详情页装载：拉条目详情；条目属于剧集（`seriesId != null`）时再拉分集并分季。
 *
 * 分集拉取失败**不影响**详情展示（退化为无剧集区）；详情拉取失败才进 [DetailUiState.Error]。
 *
 * 写操作（`02` §5.1）：
 * - [markPlayed] 成功后用**服务端权威 [UserData]** 就地更新状态，并经 [onUserDataChanged] 写回本地缓存；
 *   失败仅置 [userMessage] 提示，**不改变本地状态**（`03` §6.3）。
 * - [delete] 成功后回调 [onDeleted]（由路由层清理本地缓存并返回上一页）；失败仅提示。
 */
class DetailViewModel(
    private val source: MediaSource,
    private val itemId: String,
    private val onUserDataChanged: suspend (serverId: String, itemId: String, userData: UserData) -> Unit = { _, _, _ -> },
    private val onDeleted: (MediaItemDto) -> Unit = {},
    /**
     * 档二库存（2026-10-29）：SMB/LOCAL 的「设为隐私 / 取消隐私」写这里。
     * null = 未接（Emby 系走档一、或预览/单测）→ 隐私按钮不渲染。
     */
    private val catalog: app.myplayer.core.model.FileCatalogStore? = null,
    /** 是否处于隐私模式：只在隐私模式内提供隐私开关（普通模式下私有内容本来就不可见）。 */
    private val privacyActive: Boolean = false,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** 一次性提示（标记 / 删除失败等）；UI 展示后调 [consumeUserMessage] 清除。 */
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    fun consumeUserMessage() { _userMessage.value = null }

    /** 首次进入与失败重试共用；重复调用以最后一次为准。 */
    fun load() = viewModelScope.launch {
        // 已经有内容时**不清屏**（stale-while-revalidate）。
        // 从播放页返回时详情页会重新进入组合并再次调用 load()，若无脑重置成 Loading，
        // 页面会先闪一页转圈 —— 播放页滑出退场时露出的那片深色就是这个（2026-10-20 真机）。
        val hadContent = _state.value is DetailUiState.Content
        if (!hadContent) _state.value = DetailUiState.Loading
        runCatching { source.getItemDetail(itemId) }
            .onSuccess { item ->
                val seasons = item.seriesId
                    ?.let { runCatching { source.getEpisodes(it) }.getOrDefault(emptyList()) }
                    .orEmpty()
                    .groupBySeason()
                _state.value = DetailUiState.Content(item, seasons, isPrivate = readPrivate(item))
                // 没有元数据服务的源（SMB / 本地文件）：现场读文件头补「视频 / 音频 / 字幕信息」。
                // 刻意放在 Content **之后** —— 首屏先把标题和按钮画出来，轨道信息晚几百毫秒到达即可，
                // 不能让整页为了这几个字段转圈（探测要连 SMB 读容器头）。
                if (item.mediaStreams.isEmpty()) loadProbeInfo(item)
            }
            .onFailure { e ->
                android.util.Log.w("LimeDetail", "详情加载失败 $itemId", e)
                // 手上有旧内容就继续用，只有真的什么都没有时才展示错误页
                if (!hadContent) {
                    _state.value = DetailUiState.Error(e.message ?: "加载失败，请检查网络后重试")
                }
            }
    }

    /**
     * 探测成功后就地补进当前条目。
     *
     * 三个防御：探测失败（容器不支持）/ 页面已换成别的条目 / 状态已不是 Content —— 都安静退出，
     * 详情页表现为「没有技术信息分区」，与探测能力上线前一致。
     */
    private suspend fun loadProbeInfo(item: MediaItemDto) {
        val probe = runCatching { source.probeMedia(item) }.getOrNull() ?: return
        val current = _state.value as? DetailUiState.Content ?: return
        if (current.item.id != item.id) return
        _state.value = current.copy(
            item = current.item.copy(
                mediaStreams = probe.streams,
                // 条目时长对 SMB 恒为 0（列表接口不读文件头）→ 用探测值补上，
                // 详情页「时长」行和续播进度条才有得算
                durationTicks = probe.durationTicks.takeIf { it.value > 0 } ?: current.item.durationTicks,
            ),
        )
    }

    /** 标记 / 取消「已播放」。失败仅提示，不改本地状态。 */
    fun markPlayed(played: Boolean) = viewModelScope.launch {
        val current = _state.value as? DetailUiState.Content ?: return@launch
        runCatching { source.markPlayed(current.item, played) }
            .onSuccess { userData ->
                _state.value = current.copy(item = current.item.copy(userData = userData))
                runCatching { onUserDataChanged(source.serverId, itemId, userData) }
                    .onFailure { android.util.Log.w("LimeDetail", "已看状态写回本地缓存失败 $itemId", it) }
            }
            .onFailure { e ->
                android.util.Log.w("LimeDetail", "标记已看失败 $itemId played=$played", e)
                _userMessage.value = "操作失败，请稍后重试"
            }
    }

    /** 从服务器删除条目；成功回调 [onDeleted]（路由层清缓存 + 返回）。 */
    fun delete() = viewModelScope.launch {
        val current = _state.value as? DetailUiState.Content ?: return@launch
        runCatching { source.deleteItem(current.item) }
            .onSuccess { deleted ->
                if (deleted) onDeleted(current.item) else _userMessage.value = "该源不支持删除"
            }
            .onFailure { e ->
                android.util.Log.w("LimeDetail", "删除条目失败 $itemId", e)
                _userMessage.value = e.message ?: "删除失败，请稍后重试"
            }
    }

    /** 当前条目的档二隐私态；不支持（null）时详情页不渲染隐私按钮。 */
    private suspend fun readPrivate(item: MediaItemDto): Boolean? {
        val path = item.path ?: return null
        val store = catalog ?: return null
        if (!privacyActive) return null
        return runCatching { store.entryOf(source.serverId, path)?.isPrivate }.getOrNull()
    }

    /**
     * 设为 / 取消隐私（档二，仅 SMB/LOCAL）。成功后就地翻状态，卡片标志与列表
     * 过滤都由库存驱动（下次刷新生效）。
     */
    fun togglePrivate() = viewModelScope.launch {
        val current = _state.value as? DetailUiState.Content ?: return@launch
        val path = current.item.path ?: return@launch
        val store = catalog ?: return@launch
        val target = !(current.isPrivate == true)
        runCatching { store.setPrivate(source.serverId, listOf(path), target) }
            .onSuccess {
                (_state.value as? DetailUiState.Content)?.let { c ->
                    _state.value = c.copy(isPrivate = target)
                }
            }
            .onFailure { e ->
                android.util.Log.w("LimeDetail", "标记隐私失败 $itemId", e)
                _userMessage.value = "操作失败，请稍后重试"
            }
    }
}
