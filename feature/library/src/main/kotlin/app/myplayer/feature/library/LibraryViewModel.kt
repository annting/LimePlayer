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

    val state: StateFlow<LibraryUiState> = combine(
        repository.observeLibraries(serverId),
        selectedLibrary,
    ) { libs, selected ->
        val chosen = selected ?: libs.firstOrNull()?.id
        libs to chosen
    }.flatMapLatest { (libs, chosen) ->
        if (chosen == null) {
            flowOf(LibraryUiState(libraries = libs, loading = false))
        } else {
            repository.observeItems(serverId, chosen).map { items ->
                LibraryUiState(libraries = libs, selectedLibraryId = chosen, items = items, loading = false)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun selectLibrary(id: String) { selectedLibrary.value = id }

    /** 首次进入 / 手动刷新：先同步库列表，再逐库同步条目（顺序为契约，勿调换）。 */
    fun refresh(source: MediaSource) = viewModelScope.launch {
        repository.syncLibraries(source)
        repository.observeLibraries(serverId).first().forEach { repository.syncItems(source, it.id) }
    }
}
