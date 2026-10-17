package app.myplayer.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MediaSource

/**
 * 登录后的主界面壳：底部导航 4 tab（`03` §6.4）。
 * 默认落在「媒体库」（当前唯一有数据、有完整功能的 tab）；其余 tab 在对应里程碑填充内容。
 */
@Composable
fun MainScaffold(
    repository: LibraryRepository,
    serverConfigStore: ServerConfigStore,
    source: MediaSource?,
    serverId: String,
    onAddServer: () -> Unit,
    onPlay: (String) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.LIBRARY) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { tab.icon(LocalContentColor.current) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                MainTab.HOME -> MainPlaceholder(
                    title = "首页",
                    description = "聚合首页（继续观看 + 各库横滑海报）将在 M3 上线。",
                )
                MainTab.LIBRARY -> LibraryDestination(
                    repository = repository,
                    serverConfigStore = serverConfigStore,
                    source = source,
                    serverId = serverId,
                    onPlay = onPlay,
                )
                MainTab.SEARCH -> SearchDestination(
                    repository = repository,
                    source = source,
                    serverId = serverId,
                    onPlay = onPlay,
                )
                MainTab.SETTINGS -> MainPlaceholder(
                    title = "设置",
                    description = "服务器管理、播放偏好与关于页将在 M5 上线。",
                    actionText = "添加服务器",
                    onAction = onAddServer,
                )
            }
        }
    }
}

/** 未开发 tab 的占位页（`EmptyState` 统一视觉）。 */
@Composable
private fun MainPlaceholder(
    title: String,
    description: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = title,
            description = description,
            actionText = actionText,
            onAction = onAction,
        )
    }
}
