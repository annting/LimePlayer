package app.myplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MediaSource
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)   // flatMapLatest
class LibraryViewModel(
    private val repository: LibraryRepository,
    private val serverId: String,
) : ViewModel() {
    private val selectedLibrary = MutableStateFlow<String?>(null)
    private val sortSpec = MutableStateFlow(SortSpec())
    private val filterSpec = MutableStateFlow(FilterSpec())

    private data class Query(
        val libs: List<app.myplayer.core.model.MediaLibrary>,
        val chosen: String?,
        val sort: SortSpec,
        val filter: FilterSpec,
    )

    val state: StateFlow<LibraryUiState> = combine(
        repository.observeLibraries(serverId),
        selectedLibrary,
        sortSpec,
        filterSpec,
    ) { libs, selected, sort, filter ->
        Query(libs, selected ?: libs.firstOrNull()?.id, sort, filter)
    }.flatMapLatest { q ->
        if (q.chosen == null) {
            flowOf(LibraryUiState(libraries = q.libs, loading = false, sort = q.sort, filter = q.filter))
        } else {
            repository.observeItems(serverId, q.chosen).map { raw ->
                LibraryUiState(
                    libraries = q.libs,
                    selectedLibraryId = q.chosen,
                    // 排序筛选在本地已完成索引上瞬时执行（`02` §6.3，无 loading）
                    items = SortEngine.apply(raw, q.sort, q.filter),
                    loading = false,
                    sort = q.sort,
                    filter = q.filter,
                    totalCount = raw.size,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun selectLibrary(id: String) { selectedLibrary.value = id }

    fun setSort(spec: SortSpec) { sortSpec.value = spec }

    fun setFilter(spec: FilterSpec) { filterSpec.value = spec }

    fun resetFilter() { filterSpec.value = FilterSpec() }

    /** 首次进入 / 手动刷新：先同步库列表，再逐库同步条目（顺序为契约，勿调换）。 */
    fun refresh(source: MediaSource) = viewModelScope.launch {
        repository.syncLibraries(source)
        repository.observeLibraries(serverId).first().forEach { repository.syncItems(source, it.id) }
    }
}
