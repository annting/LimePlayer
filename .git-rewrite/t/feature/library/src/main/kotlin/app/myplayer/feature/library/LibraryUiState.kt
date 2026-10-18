package app.myplayer.feature.library

import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary

data class LibraryUiState(
    val libraries: List<MediaLibrary> = emptyList(),
    val selectedLibraryId: String? = null,
    val items: List<MediaItemDto> = emptyList(),
    val loading: Boolean = true,
    /** 当前排序规格（UI 展示「排序：时长 ↓」）。 */
    val sort: SortSpec = SortSpec(),
    /** 当前筛选（徽标显示 [FilterSpec.activeCount]）。 */
    val filter: FilterSpec = FilterSpec(),
    /** 未筛选前的原始条数（用于「筛掉 N 条」提示与空态判定）。 */
    val totalCount: Int = 0,
)
