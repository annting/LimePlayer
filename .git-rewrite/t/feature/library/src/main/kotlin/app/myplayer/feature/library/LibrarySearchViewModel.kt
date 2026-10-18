package app.myplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.PinyinConverter
import app.myplayer.core.model.SearchIndex
import kotlinx.coroutines.flow.*

/** 搜索页状态（`03` §6.2 状态机）。 */
data class SearchUiState(
    val query: String = "",
    val results: List<MediaItemDto> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    val indexSize: Int = 0,
) {
    val idle: Boolean get() = query.isBlank()
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
        combine(repository.observeAllItems(serverId), query, recent) { items, q, rec ->
            SearchUiState(
                query = q,
                results = indexOf(items).search(q),
                recentQueries = rec,
                indexSize = items.size,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** 提交检索（回车 / 点结果）→ 记入最近搜索（去重、上限 8 条）。 */
    fun rememberQuery(value: String = query.value) {
        val q = value.trim()
        if (q.isEmpty()) return
        recent.value = (listOf(q) + recent.value.filterNot { it.equals(q, ignoreCase = true) }).take(8)
    }

    fun selectRecent(value: String) {
        query.value = value
        rememberQuery(value)
    }

    fun clearQuery() {
        query.value = ""
    }
}
