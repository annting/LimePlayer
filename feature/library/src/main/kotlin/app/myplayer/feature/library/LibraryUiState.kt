package app.myplayer.feature.library

import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary

data class LibraryUiState(
    val libraries: List<MediaLibrary> = emptyList(),
    val selectedLibraryId: String? = null,
    val items: List<MediaItemDto> = emptyList(),
    val loading: Boolean = true,
)
