package app.myplayer.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.myplayer.core.model.CastMember
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.UserData

/**
 * 详情页入口：装载 `getItemDetail`（剧集再拉分集），渲染 [DetailScreen]。
 *
 * [loadImage] 由 `:app` 注入（复用其「服务端海报 + 本地抽帧兜底」策略），
 * 本模块不直接依赖 `LocalThumbnailProvider`。
 *
 * 写操作接线（`02` §5.1，详情页二期）：
 * - [onUserDataChanged]：标记已看成功后写回本地缓存（`:app` 注入 repository 更新）；
 * - [onDeleted]：服务端删除成功后由 `:app` 清理本地缓存并返回上一页；
 * - [onPersonClick]：跳转人物页（`person/{personId}` 路由，`:app` 接线）。
 */
@Composable
fun DetailRoute(
    itemId: String,
    source: MediaSource,
    loadImage: suspend (MediaItemDto, ImageKind) -> ImageSource,
    onBack: () -> Unit,
    onPlay: (MediaItemDto) -> Unit,
    onUserDataChanged: suspend (serverId: String, itemId: String, userData: UserData) -> Unit = { _, _, _ -> },
    onDeleted: (MediaItemDto) -> Unit = {},
    onPersonClick: (CastMember) -> Unit = {},
    /** 档二库存（SMB/LOCAL 的隐私开关写这里）；null = 不支持 → 隐私按钮不渲染。 */
    catalog: app.myplayer.core.model.FileCatalogStore? = null,
    /** 是否处于隐私模式：普通模式下不提供隐私开关（私有内容本来就不可见）。 */
    privacyActive: Boolean = false,
) {
    val vm: DetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                DetailViewModel(
                    source = source,
                    itemId = itemId,
                    onUserDataChanged = onUserDataChanged,
                    onDeleted = onDeleted,
                    catalog = catalog,
                    privacyActive = privacyActive,
                )
            }
        },
    )
    LaunchedEffect(itemId, source) { vm.load() }
    val state by vm.state.collectAsStateWithLifecycle()
    val userMessage by vm.userMessage.collectAsStateWithLifecycle()

    DetailScreen(
        state = state,
        loadImage = loadImage,
        loadPersonImage = { id, size -> source.personImage(id, size) },
        canMarkPlayed = source.canMarkPlayed,
        onBack = onBack,
        onPlay = onPlay,
        onRetry = { vm.load() },
        onMarkPlayed = { played -> vm.markPlayed(played) },
        onDelete = { vm.delete() },
        onPersonClick = onPersonClick,
        userMessage = userMessage,
        onMessageShown = { vm.consumeUserMessage() },
        onTogglePrivate = { vm.togglePrivate() },
    )
}
