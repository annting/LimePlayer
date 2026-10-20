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
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** 一次性提示（标记 / 删除失败等）；UI 展示后调 [consumeUserMessage] 清除。 */
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    fun consumeUserMessage() { _userMessage.value = null }

    /** 首次进入与失败重试共用；重复调用以最后一次为准。 */
    fun load() = viewModelScope.launch {
        _state.value = DetailUiState.Loading
        runCatching { source.getItemDetail(itemId) }
            .onSuccess { item ->
                val seasons = item.seriesId
                    ?.let { runCatching { source.getEpisodes(it) }.getOrDefault(emptyList()) }
                    .orEmpty()
                    .groupBySeason()
                _state.value = DetailUiState.Content(item, seasons)
            }
            .onFailure { e ->
                android.util.Log.w("LimeDetail", "详情加载失败 $itemId", e)
                _state.value = DetailUiState.Error(e.message ?: "加载失败，请检查网络后重试")
            }
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
}
