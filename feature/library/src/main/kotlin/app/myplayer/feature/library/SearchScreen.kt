package app.myplayer.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.LocalBottomNavSpace
import app.myplayer.core.designsystem.MyPlayerTheme
import app.myplayer.core.designsystem.components.AppChip
import app.myplayer.core.designsystem.components.AppConfirmDialog
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.core.designsystem.components.PageHeader
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.UserData

/**
 * 搜索页（`03` §6.2 / 任务 3-6）：输入即搜、搜索历史 chips、结果复用库页网格。
 *
 * 页面结构：
 * - 顶部 [OutlinedTextField]，有输入时带一键清除（X）；
 * - 输入为空 → [SearchHistorySection]（三行折叠、可展开、可清空）；
 * - 输入不为空 → 3 列 [LazyVerticalGrid]，卡片复用 [EmbyPosterCard]。
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSelectRecent: (String) -> Unit,
    onClear: () -> Unit,
    onClearHistory: () -> Unit,
    onToggleHistoryExpanded: () -> Unit,
    loadImage: suspend (MediaItemDto) -> ImageSource,
    onOpenDetail: (MediaItemDto) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = "搜索",
            subtitle = if (state.searchResults.isNotEmpty()) "${state.searchResults.size} 个结果" else null,
        )

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(focus),
            placeholder = { Text("输入片名 / 拼音 / 首字母") },
            singleLine = true,
            shape = AppShape.control,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "清除输入",
                            tint = app.text3,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
        )

        Spacer(Modifier.height(12.dp))

        when {
            // 首帧：本地索引尚未发射（通常一闪而过）
            state.isLoading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = scheme.primary)
            }

            // 历史：输入为空
            state.isIdle -> {
                if (state.historyList.isEmpty()) {
                    EmptyState(
                        title = "搜索你的媒体库",
                        description = "支持中文、拼音全拼（jianfei）与首字母（jf），输入即搜。",
                    )
                } else {
                    SearchHistorySection(
                        history = state.historyList,
                        isExpanded = state.isHistoryExpanded,
                        // Chip 本身可获焦，点完会把焦点从输入框抢走；回填关键词后立刻还回去
                        onSelect = { q ->
                            onSelectRecent(q)
                            focus.requestFocus()
                        },
                        onToggleExpanded = onToggleHistoryExpanded,
                        onClearHistory = onClearHistory,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            state.searchResults.isEmpty() -> EmptyState(
                title = "没有找到结果",
                description = "换个关键词试试，或减少字数（支持标题任意位置匹配）。",
            )

            else -> LazyVerticalGrid(
                // 与媒体库详情页完全一致：强制 3 列、8dp 间距
                columns = GridCells.Fixed(SEARCH_COLUMN_COUNT),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    // 悬浮导航高度（含手势条 + 呼吸）
                    bottom = LocalBottomNavSpace.current,
                ),
                horizontalArrangement = Arrangement.spacedBy(SEARCH_GRID_SPACING.dp),
                verticalArrangement = Arrangement.spacedBy(SEARCH_GRID_SPACING.dp),
            ) {
                items(state.searchResults, key = { it.item.id }) { result ->
                    EmbyPosterCard(
                        item = result.item,
                        selected = false,
                        selectionMode = false,
                        // 进度与搜索结果同源；卡片内部只在 (0,1) 区间渲染青柠绿进度条
                        progress = result.progress,
                        loadImage = { item, _ -> loadImage(item) },
                        onClick = { onOpenDetail(result.item) },
                        onLongClick = {},
                    )
                }
            }
        }
    }
}

/**
 * 搜索历史：标题行（右侧垃圾桶清空）+ FlowRow chips + 三行折叠。
 *
 * - 默认只展示 [SearchUiState.HISTORY_COLLAPSED_LIMIT] 条；
 * - 总数超过 12 条时，在最后一个 chip 后追加展开/收起箭头；
 * - 清空是破坏性操作，先弹 [AppConfirmDialog] 二次确认（20dp 圆角的紧凑弹窗）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistorySection(
    history: List<String>,
    isExpanded: Boolean,
    onSelect: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    val canCollapse = history.size > SearchUiState.HISTORY_COLLAPSED_LIMIT
    val displayedHistory = if (isExpanded) history else history.take(SearchUiState.HISTORY_COLLAPSED_LIMIT)

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 展开后可能有上百条，必须可滚动，否则超出屏幕的部分看不到
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = LocalBottomNavSpace.current),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "搜索历史",
                style = MaterialTheme.typography.labelLarge,
                color = app.text3,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(AppShape.control)
                    .clickable { showClearConfirm = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "清空搜索历史",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            displayedHistory.forEach { q ->
                AppChip(text = q, selected = false, onClick = { onSelect(q) })
            }
            if (canCollapse) {
                HistoryExpandButton(expanded = isExpanded, onClick = onToggleExpanded)
            }
        }
    }

    if (showClearConfirm) {
        // 紧凑确认弹窗：20dp 圆角 / 20dp 内边距 / 标题·正文·按钮间距 20–8–12dp。
        // 不用 M3 AlertDialog —— 短文案时它三段各带 24dp 内边距，看着很空（见 AppConfirmDialog 注释）
        AppConfirmDialog(
            title = "清空搜索历史",
            message = "确定要清空所有搜索历史吗？",
            confirmText = "清空",
            onConfirm = {
                onClearHistory()
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

/** 历史展开/收起按钮：32dp 方块与 Chip 同高，箭头随状态切换。 */
@Composable
private fun HistoryExpandButton(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(AppShape.control)
            .background(app.surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "收起搜索历史" else "展开搜索历史",
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ---- @Preview ----

@Preview(name = "搜索 · 结果网格 · 暗色", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 900)
@Composable
private fun SearchResultsPreview() {
    MyPlayerTheme(darkTheme = true) {
        SearchScreen(
            state = SearchUiState(
                searchQuery = "jianfei",
                historyList = listOf("减肥", "jf", "星际穿越"),
                searchResults = SearchPreviewData.results,
                isLoading = false,
            ),
            onQueryChange = {},
            onSubmit = {},
            onSelectRecent = {},
            onClear = {},
            onClearHistory = {},
            onToggleHistoryExpanded = {},
            loadImage = { item -> ImageSource.Remote("https://picsum.photos/seed/${item.id}/300/450") },
            onOpenDetail = {},
            autoFocus = false,
        )
    }
}

@Preview(name = "搜索 · 历史折叠（12 条）· 暗色", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 700)
@Composable
private fun SearchHistoryCollapsedPreview() {
    MyPlayerTheme(darkTheme = true) {
        SearchScreen(
            state = SearchUiState(
                searchQuery = "",
                historyList = SearchPreviewData.history,
                isHistoryExpanded = false,
                isLoading = false,
            ),
            onQueryChange = {},
            onSubmit = {},
            onSelectRecent = {},
            onClear = {},
            onClearHistory = {},
            onToggleHistoryExpanded = {},
            loadImage = { ImageSource.None },
            onOpenDetail = {},
            autoFocus = false,
        )
    }
}

@Preview(name = "搜索 · 历史展开 · 暗色", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 700)
@Composable
private fun SearchHistoryExpandedPreview() {
    MyPlayerTheme(darkTheme = true) {
        SearchScreen(
            state = SearchUiState(
                searchQuery = "",
                historyList = SearchPreviewData.history,
                isHistoryExpanded = true,
                isLoading = false,
            ),
            onQueryChange = {},
            onSubmit = {},
            onSelectRecent = {},
            onClear = {},
            onClearHistory = {},
            onToggleHistoryExpanded = {},
            loadImage = { ImageSource.None },
            onOpenDetail = {},
            autoFocus = false,
        )
    }
}

/** Preview 假数据：进度覆盖 0（无进度条）/ 中间值（有进度条）/ 1（已看完，不画进度条）三种情况。 */
private object SearchPreviewData {
    private val progressValues = listOf(0f, 0.32f, 1f, 0.68f, 0f, 0.15f, 1f, 0.45f, 0f)

    val history: List<String> = listOf(
        "减肥", "jf", "星际穿越", "沙丘", "spiderman", "蝙蝠侠",
        "流浪地球", "三体", "进击的巨人", "鬼灭之刃", "奥本海默",
        "芭比", "沙丘 2", "周处除三害", "热辣滚烫", "飞驰人生",
    )

    val results: List<SearchResultItem> = listOf(
        "减肥计划", "健飞日记", "剑锋行动", "间谍过家家", "件飞往事",
        "煎飞时刻", "简·菲的故事", "茧飞", "见飞如面",
    ).mapIndexed { index, title ->
        SearchResultItem(
            item = MediaItemDto(
                id = "preview-$index",
                serverId = "preview",
                sourceType = SourceType.EMBY,
                title = title,
                durationTicks = Ticks(100L),
                userData = UserData(resumePositionTicks = Ticks((progressValues[index] * 100).toLong())),
            ),
            progress = progressValues[index],
        )
    }
}

// ---- 常量 ----

/** 结果网格列数：与媒体库详情页一致，强制 3 列（不要 Adaptive）。 */
private const val SEARCH_COLUMN_COUNT = 3

/** 结果网格间距（与媒体库详情页一致 8dp）。 */
private const val SEARCH_GRID_SPACING = 8
