package app.myplayer.nav

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MediaSource
import app.myplayer.feature.library.LibraryScreen
import app.myplayer.feature.library.LibraryViewModel

/**
 * 只做「路由 + 屏幕装配」，不含业务逻辑。
 *
 * - [serverId] 为 null → 落到引导页；非 null → 直接进库页。
 * - [source] 由任务 1-14 的 `SourceFactory` 提供；为 null 时库页只读 Room（图/数据都还是空的），不崩。
 */
@Composable
fun AppNavHost(
    repository: LibraryRepository,
    serverId: String?,
    source: MediaSource?,
    onAddServer: () -> Unit,
) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = if (serverId == null) Routes.WELCOME else Routes.LIBRARY) {

        composable(Routes.WELCOME) { NoServerScreen(onAddServer) }

        composable(Routes.LIBRARY) {
            val vm: LibraryViewModel = viewModel(
                key = "library-$serverId",
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        LibraryViewModel(repository, serverId!!) as T
                },
            )
            val state by vm.state.collectAsStateWithLifecycle()

            // 首次进入即同步一次（分页落 Room，UI 只读 Room）
            LaunchedEffect(serverId) { source?.let { vm.refresh(it) } }

            LibraryScreen(
                state = state,
                loadImage = { item ->
                    source?.image(item, ImageKind.POSTER, ImageSize.MEDIUM) ?: ImageSource.None
                },
                onSelectLibrary = vm::selectLibrary,
            )
        }
    }
}

@Composable
private fun NoServerScreen(onAddServer: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("还没有添加服务器", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "添加一台 Emby 或飞牛影视服务器后即可开始浏览。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddServer, modifier = Modifier.height(48.dp)) { Text("添加服务器") }
    }
}
