package app.myplayer.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.core.model.ClientCapabilities
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
    caps: ClientCapabilities,
    localThumbnails: Boolean,
    settingsStore: app.myplayer.core.datastore.SettingsStore,
    tokenStore: app.myplayer.core.datastore.TokenStore,
    onAddServer: () -> Unit,
    onPlay: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.LIBRARY) }

    Scaffold(
        bottomBar = {
            // 底部导航（`03` §6.4）。Material3 `NavigationBar` 默认把 80dp 内容高度
            // **加上**底部手势条 inset（edge-to-edge 下约 24dp），于是视觉上
            // 「文字到栏底」的留白远大于「图标到栏顶」（2026-10-19 真机反馈）。
            //
            // 处理：① 栏本体固定 64dp，图标 + 文字在栏内垂直居中；
            // ② 系统手势条的 24dp 交给**栏外**的同高度 Spacer（页面背景色），
            //    不再染成栏背景 —— 否则那 24dp 看起来就是"栏里多出来的底部留白"。
            Column {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBar(
                        containerColor = Color.Transparent,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        modifier = Modifier.height(64.dp),
                    ) {
                        MainTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                icon = { tab.icon(LocalContentColor.current) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
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
                    caps = caps,
                    localThumbnails = localThumbnails,
                    onPlay = onPlay,
                    onOpenDetail = onOpenDetail,
                )
                MainTab.SEARCH -> SearchDestination(
                    repository = repository,
                    source = source,
                    serverId = serverId,
                    caps = caps,
                    localThumbnails = localThumbnails,
                    onPlay = onPlay,
                    onOpenDetail = onOpenDetail,
                )
                MainTab.SETTINGS -> SettingsDestination(
                    settingsStore = settingsStore,
                    serverConfigStore = serverConfigStore,
                    tokenStore = tokenStore,
                    onAddServer = onAddServer,
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
