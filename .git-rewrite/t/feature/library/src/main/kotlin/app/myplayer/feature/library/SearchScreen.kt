package app.myplayer.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.components.AppChip
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.core.designsystem.components.PageHeader
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto

/**
 * 搜索页（`03` §6.2 / 任务 3-6）：输入即搜、最近搜索 chips、结果复用库页网格。
 *
 * 状态机：历史（输入为空）→ 有结果 → 无结果；索引来自本地已同步条目（[SearchUiState.indexSize]）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSelectRecent: (String) -> Unit,
    onClear: () -> Unit,
    loadImage: suspend (MediaItemDto) -> ImageSource,
    onPlay: (MediaItemDto) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val app = LocalAppColors.current
    val focus = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = "搜索",
            subtitle = if (state.indexSize > 0) "已索引 ${state.indexSize} 条" else "索引尚未建立",
        )

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(focus),
            placeholder = { Text("输入片名 / 拼音 / 首字母") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    Text(
                        "清空",
                        style = MaterialTheme.typography.labelMedium,
                        color = app.text3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = onClear)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            },
        )

        Spacer(Modifier.height(12.dp))

        when {
            // 历史：输入为空
            state.idle -> {
                if (state.recentQueries.isEmpty()) {
                    EmptyState(
                        title = "搜索你的媒体库",
                        description = "支持中文、拼音全拼（jianfei）与首字母（jf），输入即搜。",
                    )
                } else {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            "最近搜索",
                            style = MaterialTheme.typography.labelLarge,
                            color = app.text3,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.recentQueries.forEach { q ->
                                AppChip(text = q, selected = false, onClick = { onSelectRecent(q) })
                            }
                        }
                    }
                }
            }

            state.results.isEmpty() -> EmptyState(
                title = "没有找到结果",
                description = "换个关键词试试，或减少字数（支持标题任意位置匹配）。",
            )

            else -> {
                Text(
                    "${state.results.size} 个结果",
                    style = MaterialTheme.typography.labelMedium,
                    color = app.text3,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 132.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(state.results, key = { it.id }) { item ->
                        PosterCard(
                            item = item,
                            loadImage = loadImage,
                            onPlay = { onPlay(it); onSubmit() },
                        )
                    }
                }
            }
        }
    }
}
