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
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.datastore.TokenStore
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.SourceType
import app.myplayer.feature.library.LibraryScreen
import app.myplayer.feature.library.LibraryViewModel
import app.myplayer.feature.server.AddServerScreen
import app.myplayer.feature.server.AddServerViewModel
import app.myplayer.feature.server.LoginScreen
import app.myplayer.feature.server.LoginViewModel
import app.myplayer.feature.server.ProbeStatus
import app.myplayer.feature.server.normalizeBaseUrl
import app.myplayer.feature.server.probeServer
import app.myplayer.source.httpapi.HttpMediaApi
import app.myplayer.source.httpapi.SourceConfig
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.UUID

/** 选源结果在两页之间传递（避免把状态编码进路由参数）。 */
private data class PendingServer(
    val serverId: String,
    val sourceType: SourceType,
    val baseUrl: String,
)

/**
 * 只做「路由 + 屏幕装配」，不含业务逻辑。
 *
 * - [serverId] 为 null → 落到引导页；非 null → 直接进库页。
 * - [source] 由 `SourceFactory` 提供；为 null 时库页只读 Room（图/数据都还是空的），不崩。
 */
@Composable
fun AppNavHost(
    repository: LibraryRepository,
    serverConfigStore: ServerConfigStore,
    tokenStore: TokenStore,
    client: OkHttpClient,
    serverId: String?,
    source: MediaSource?,
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingServer?>(null) }

    NavHost(nav, startDestination = if (serverId == null) Routes.WELCOME else Routes.LIBRARY) {

        composable(Routes.WELCOME) {
            NoServerScreen(onAddServer = { nav.navigate(Routes.ADD_SERVER) })
        }

        composable(Routes.ADD_SERVER) {
            val vm = remember { AddServerViewModel(probe = { base, port -> probeServer(client, base, port) }) }
            val state by vm.state.collectAsStateWithLifecycle()
            AddServerScreen(
                state = state,
                onAddressChange = vm::onAddressChange,
                onPortChange = vm::onPortChange,
                onTest = { scope.launch { vm.onTest() } },
                onProceed = {
                    val s = state
                    if (s.probeStatus is ProbeStatus.Reachable) {
                        pending = PendingServer(
                            serverId = UUID.randomUUID().toString(),
                            sourceType = s.detectedSourceType,
                            baseUrl = normalizeBaseUrl(s.address, s.port.toIntOrNull() ?: 8096),
                        )
                        nav.navigate(Routes.LOGIN)
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.LOGIN) {
            val target = pending
            if (target == null) {
                // 异常路径（例如深链直达）：退回引导页，不显示空白页
                LaunchedEffect(Unit) { nav.popBackStack(Routes.WELCOME, inclusive = false) }
            } else {
                val api = remember(target) {
                    HttpMediaApi(
                        client = client,
                        config = SourceConfig(serverId = target.serverId, baseUrl = target.baseUrl),
                    )
                }
                val loginVm = remember(target) {
                    LoginViewModel(
                        serverId = target.serverId,
                        sourceType = target.sourceType,
                        baseUrl = target.baseUrl,
                        login = api::authenticateByName,
                        configStore = serverConfigStore,
                        tokenStore = tokenStore,
                    )
                }
                val loginState by loginVm.state.collectAsStateWithLifecycle()
                LoginScreen(
                    state = loginState,
                    onUsernameChange = loginVm::onUsernameChange,
                    onPasswordChange = loginVm::onPasswordChange,
                    onSubmit = { scope.launch { loginVm.submit() } },
                    onLoggedIn = {
                        // 登录已把 ServerConfig/Token 落盘 → 回到根级，MainActivity 会重新造出 source
                        nav.navigate(Routes.LIBRARY) { popUpTo(0) }
                    },
                )
            }
        }

        composable(Routes.LIBRARY) {
            val sid = serverId
            if (sid == null) {
                // 配置尚未就绪（登录落盘与重组的短暂过渡态）：避免 serverId!! 崩溃
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val vm: LibraryViewModel = viewModel(
                    key = "library-$sid",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            LibraryViewModel(repository, sid) as T
                    },
                )
                val state by vm.state.collectAsStateWithLifecycle()

                // 首次进入即同步一次（分页落 Room，UI 只读 Room）
                LaunchedEffect(sid, source) { source?.let { vm.refresh(it) } }

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
