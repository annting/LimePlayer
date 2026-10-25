package app.myplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.PinyinConverter
import app.myplayer.core.model.SearchIndex
import app.myplayer.core.model.resumeFraction
import kotlinx.coroutines.flow.*

/**
 * 一条搜索结果：媒体条目 + 播放进度。
 *
 * [progress] 是 `0f..1f` 的续播占比（口径见 `MediaItemDto.resumeFraction()`），
 * 卡片只在 `progress > 0f && progress < 1f` 时渲染进度条。
 */
data class SearchResultItem(
    val item: MediaItemDto,
    val progress: Float,
)

/** 搜索页状态（`03` §6.2 状态机）。 */
data class SearchUiState(
    /** 输入框里的关键词。 */
    val searchQuery: String = "",
    /** 搜索历史（去重、最多 [HISTORY_LIMIT] 条）。 */
    val historyList: List<String> = emptyList(),
    /** 历史是否已展开（默认只显示三行，见 [HISTORY_COLLAPSED_LIMIT]）。 */
    val isHistoryExpanded: Boolean = false,
    /** 当前匹配结果（带播放进度）。 */
    val searchResults: List<SearchResultItem> = emptyList(),
    /** 索引是否就绪；首帧为 true，数据流首次发射后转 false。 */
    val isLoading: Boolean = true,
) {
    /** 输入为空 = 历史态。 */
    val isIdle: Boolean get() = searchQuery.isBlank()

    companion object {
        /** 历史最多保存条数（用户 2026-10-24 定稿 100）。 */
        const val HISTORY_LIMIT = 100

        /** 历史默认只展示 12 条（约三行），超出后出现展开图标。 */
        const val HISTORY_COLLAPSED_LIMIT = 12
    }
}

/**
 * 搜索（任务 3-5 / 3-6）：本地索引 + 输入即搜。
 *
 * 索引在条目列表变化时重建一次并缓存，输入变化只做打分（`02` §6.5）。
 * 最近搜索目前保存在内存（重启清空）—— 落盘待 M5 设置页统一处理 DataStore 时一并接。
 */
class LibrarySearchViewModel(
    repository: LibraryRepository,
    serverId: String,
    private val pinyin: PinyinConverter,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val recent = MutableStateFlow<List<String>>(emptyList())
    private val expanded = MutableStateFlow(false)

    @Volatile private var cachedFor: List<MediaItemDto>? = null
    @Volatile private var cachedIndex: SearchIndex? = null

    private fun indexOf(items: List<MediaItemDto>): SearchIndex {
        val cached = cachedIndex
        if (cached != null && cachedFor === items) return cached
        val built = SearchIndex.build(items, pinyin)
        cachedFor = items
        cachedIndex = built
        return built
    }

    val state: StateFlow<SearchUiState> =
        combine(repository.observeAllItems(serverId), query, recent, expanded) { items, q, rec, isExpanded ->
            SearchUiState(
                searchQuery = q,
                historyList = rec,
                isHistoryExpanded = isExpanded,
                searchResults = indexOf(items).search(q).map { SearchResultItem(it, it.resumeFraction()) },
                isLoading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** 提交检索（回车 / 点历史）→ 记入搜索历史（去重、上限 [SearchUiState.HISTORY_LIMIT] 条）。 */
    fun rememberQuery(value: String = query.value) {
        val q = value.trim()
        if (q.isEmpty()) return
        recent.value = (listOf(q) + recent.value.filterNot { it.equals(q, ignoreCase = true) })
            .take(SearchUiState.HISTORY_LIMIT)
    }

    fun selectRecent(value: String) {
        query.value = value
        rememberQuery(value)
    }

    fun clearQuery() {
        query.value = ""
    }

    /** 展开 / 收起搜索历史（默认只显示三行）。 */
    fun toggleHistoryExpanded() {
        expanded.value = !expanded.value
    }

    /** 清空全部搜索历史（确认弹窗点「清空」后调用），同时回到折叠态。 */
    fun clearRecent() {
        recent.value = emptyList()
        expanded.value = false
    }
}
