package app.myplayer.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.myplayer.core.designsystem.AppRadius
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.Spacing
import app.myplayer.core.designsystem.components.BackIcon
import app.myplayer.core.designsystem.components.CircleIconButton
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.core.model.CastMember
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaSource
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 人物页（`docs/03` §6.3.1，详情页二期）：顶部头像 + 姓名 → 作品海报网格。
 * 点作品卡片进该条目的详情页；无中央播放按钮。
 */

/** 人物页状态：装载中 / 空（源不支持或无数据）/ 作品列表。 */
sealed interface PersonUiState {
    data object Loading : PersonUiState
    data object Empty : PersonUiState
    data class Content(val works: List<MediaItemDto>) : PersonUiState
}

/** 人物作品装载：`getPersonWorks(personId)`；失败按空态处理（`03` §6.3.1）。 */
class PersonViewModel(
    private val source: MediaSource,
    private val personId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<PersonUiState>(PersonUiState.Loading)
    val state: StateFlow<PersonUiState> = _state.asStateFlow()

    fun load() = viewModelScope.launch {
        _state.value = PersonUiState.Loading
        runCatching { source.getPersonWorks(personId) }
            .onSuccess { works ->
                _state.value = if (works.isEmpty()) PersonUiState.Empty else PersonUiState.Content(works)
            }
            .onFailure { e ->
                android.util.Log.w("LimeDetail", "人物作品加载失败 $personId", e)
                _state.value = PersonUiState.Empty
            }
    }
}

/** 人物页入口：`:app` 的 `person/{personId}` 路由挂载。 */
@Composable
fun PersonRoute(
    personId: String,
    personName: String,
    source: MediaSource,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onBack: () -> Unit,
    onItemClick: (MediaItemDto) -> Unit,
) {
    val vm: PersonViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PersonViewModel(source, personId) }
        },
    )
    LaunchedEffect(personId, source) { vm.load() }
    val state by vm.state.collectAsStateWithLifecycle()

    PersonScreen(
        state = state,
        personName = personName,
        avatar = rememberPersonImage(personId) { id, size -> source.personImage(id, size) },
        loadImage = loadImage,
        onBack = onBack,
        onItemClick = onItemClick,
    )
}

@Composable
fun PersonScreen(
    state: PersonUiState,
    personName: String,
    avatar: ImageSource,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onBack: () -> Unit,
    onItemClick: (MediaItemDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶栏：返回键常驻（Loading/Empty/Content 三态都能返回），不再悬浮压头像（BUG-1）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(onClick = onBack) {
                BackIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        when (state) {
            PersonUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            PersonUiState.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "该源暂无演职员数据",
                    description = "服务器未刮削该演职员的信息",
                )
            }
            is PersonUiState.Content -> Column(Modifier.fillMaxSize()) {
                PersonHeader(personName, avatar)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        // 28dp 呼吸留白 + 手势条：MainActivity 不再统一避让底部
                        // （首页悬浮导航要透底），演员页要自己让出来
                        bottom = 28.dp +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(Dimens.itemGap),
                ) {
                    items(state.works, key = { it.id }) { work ->
                        WorkCard(work, rememberItemImage(work, ImageKind.POSTER, loadImage), onItemClick)
                    }
                }
            }
        }
    }
}

/** 人物头部（`03` §6.3.1）：80dp 圆头像（无图用首字占位）+ 姓名。 */
@Composable
private fun PersonHeader(name: String, avatar: ImageSource) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pagePadding, vertical = Spacing.lg.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val request = rememberImageRequest(avatar)
        Box(
            Modifier
                .size(80.dp)
                .clip(AppShape.circle)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    name.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 作品海报卡（2:3，复用库页卡片密度；`03` §6.3.1）。 */
@Composable
private fun WorkCard(
    work: MediaItemDto,
    poster: ImageSource,
    onItemClick: (MediaItemDto) -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(AppRadius.poster.dp))
            .clickable { onItemClick(work) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(AppRadius.poster.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val request = rememberImageRequest(poster)
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = work.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            work.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
