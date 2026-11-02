package app.myplayer.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.myplayer.LocalThumbnailProvider
import app.myplayer.core.datastore.LocalRoot
import app.myplayer.core.datastore.LocalRootStore
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.components.AddIcon
import app.myplayer.core.designsystem.components.AppListDialog
import app.myplayer.core.designsystem.components.AppButton
import app.myplayer.core.designsystem.components.AppButtonStyle
import app.myplayer.core.designsystem.components.CircleIconButton
import app.myplayer.core.designsystem.components.SectionLabel
import app.myplayer.core.designsystem.components.SectionLabelVariant
import app.myplayer.core.designsystem.components.EmptyState
import app.myplayer.core.designsystem.components.FilterListIcon
import app.myplayer.core.designsystem.components.MoreVerticalIcon
import app.myplayer.core.designsystem.components.PageHeader
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.Spacing
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.ItemQuery
import app.myplayer.core.model.LocalIgnoreStore
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary
import app.myplayer.feature.library.FilterSpec
import app.myplayer.feature.library.LocalFolderGrid
import app.myplayer.feature.library.SortSpec
import app.myplayer.source.local.LocalMediaSource
import app.myplayer.source.local.allFilesAccessIntent
import app.myplayer.source.local.allFilesAccessSupported
import app.myplayer.source.local.isTreeUriGranted
import app.myplayer.source.local.persistTreeUriPermission
import app.myplayer.source.local.releaseTreeUriPermission
import kotlinx.coroutines.launch

/**
 * 「本机」tab（`docs/12-本机视频播放方案.md`）。
 *
 * ## 两条读取通道（§3.1）
 * - **全盘访问**：用户在系统设置里开「所有文件访问权限」（`MANAGE_EXTERNAL_STORAGE`），
 *   之后用 `File` 直扫整块外部存储（含 `Download` 根），扫描快；
 * - **选择文件夹（SAF）**：逐棵授权目录，不需要任何特殊权限，可加多个根。
 *
 * ## 首次进入的交互（2026-11-01 定稿）
 * 页面**中间**并排给两张方案卡，用户自己选一条；不自动弹任何系统界面。
 * 用户一旦走了 SAF，「全盘访问」入口就收进右上角「⋮」管理页，不再占页面中间。
 *
 * ## 多根授权（§4.1）
 * Android 11+ 禁止选择内部存储根，「整台手机」只能由多棵子树拼出来。所以：
 * - 头部「＋」随时追加一根（Movies + DCIM + …；全盘档下隐藏），各根**合并**成一个库列表；
 * - 右上角「⋮」管理已授权根（可移除、可见失效标记）与已忽略文件夹；
 * - 祖先/后代重复授权由 `LocalRootStore` 去重，只保留最宽的根，避免路径前缀二义。
 *
 * ⚠️ 合规说明：两条通道都走系统提供的合法机制（SAF 授权 / 特殊权限开关），
 * 系统权限机制**不可绕过**；这里绝不实现任何提权 / 静默授权。
 */
@Composable
fun LocalDestination(
    localSource: LocalMediaSource?,
    localRootStore: LocalRootStore,
    localRoots: List<LocalRoot>,
    localIgnoreStore: LocalIgnoreStore,
    caps: ClientCapabilities,
    onPlay: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    allFilesAccess: Boolean = false,
    /** 进了文件夹（二级页）时回传 true，外层据此收起悬浮导航。 */
    onSubPageChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var denied by rememberSaveable { mutableStateOf(false) }
    // 同一 treeUri 重复授权时 LocalRootStore 的 flow 可能不变 → 用 tick 强制重算权限
    var authTick by remember { mutableIntStateOf(0) }
    var manageVisible by remember { mutableStateOf(false) }    // 管理页要展示的「已忽略」集合（ignore store 是 suspend，不能在组合里直接读）
    var ignoredFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    val localServerId = localSource?.serverId ?: LocalMediaSource.DEFAULT_SERVER_ID

    LaunchedEffect(manageVisible, authTick) {
        if (manageVisible) ignoredFolders = localIgnoreStore.ignoredFolders(localServerId)
    }

    // 全盘访问只能去系统设置开：把用户送过去，回到前台由 MainActivity 的 ON_RESUME 重查
    val openAllFilesSettings: () -> Unit = {
        if (allFilesAccessSupported()) {
            runCatching { context.startActivity(allFilesAccessIntent(context)) }
                .onFailure {
                    Toast.makeText(context, "无法打开系统设置，请手动到「设置 → 应用 → 特殊权限」开启", Toast.LENGTH_LONG).show()
                }
        } else {
            Toast.makeText(context, "当前系统不支持全盘访问，请用「选择文件夹」", Toast.LENGTH_SHORT).show()
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTreeUriPermission(context, uri)
            scope.launch {
                // add 而不是 save：多根累加（同族根由 store 内部去重，保留最宽的一根）
                val added = localRootStore.add(uri.toString())
                if (!added) {
                    // 同一条目录 / 已被更宽的根覆盖：明确告知，否则用户会以为"添加没生效"
                    Toast.makeText(
                        context,
                        "该文件夹已在授权范围内，无需重复添加",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                denied = false
                authTick++
            }
        } else {
            // 用户取消：回到选择页并说明，不静默停在空白
            denied = true
        }
    }

    when {
        localSource != null -> LocalAuthorized(
            source = localSource,
            ignoreStore = localIgnoreStore,
            caps = caps,
            onPlay = onPlay,
            onOpenDetail = onOpenDetail,
            onAddRoot = { launcher.launch(null) },
            onManage = { manageVisible = true },
            allFilesAccess = allFilesAccess,
            // 根增删 / 恢复忽略都发生在本页之外（「⋮」弹窗、SAF 回调），
            // 源实例不变（同一个 treeUri 重新授权时 flow 也不变）→ 必须显式戳一下重扫，
            // 否则恢复忽略后列表还是空的（2026-11-02 真机踩到）。
            refreshSignal = authTick,
            onSubPageChange = onSubPageChange,
        )

        // 未授权只有**一个**门槛页（2026-11-01 用户反馈：选择页 + 「未获得权限」页是重复的两道门）。
        // 取消授权、授权失效都不再切页，只在选择页上加一行提示。
        else -> PermissionChooserState(
            allFilesSupported = allFilesAccessSupported(),
            cancelled = denied,
            revoked = !denied && localRoots.isNotEmpty(),
            canManage = localRoots.isNotEmpty(),
            onAllFiles = openAllFilesSettings,
            onSaf = { denied = false; launcher.launch(null) },
            onOpenSettings = { openAppSettings(context) },
            onManage = { manageVisible = true },
        )
    }

    if (manageVisible) {
        ManageRootsDialog(
            roots = localRoots,
            isGranted = { isTreeUriGranted(context, it) },
            ignored = ignoredFolders,
            allFilesAccess = allFilesAccess,
            allFilesSupported = allFilesAccessSupported(),
            onAdd = { launcher.launch(null) },
            onRequestAllFiles = { manageVisible = false; openAllFilesSettings() },
            onRemove = { root ->
                releaseTreeUriPermission(context, root.treeUri)
                scope.launch { localRootStore.remove(root.treeUri) }
                authTick++
            },
            onRestoreIgnored = { folder ->
                scope.launch {
                    localIgnoreStore.restore(localServerId, folder)
                    ignoredFolders = localIgnoreStore.ignoredFolders(localServerId)
                    authTick++
                }
            },
            onDismiss = { manageVisible = false },
        )
    }
}

// ---------------------------------------------------------------- 授权前后状态

/**
 * 未授权状态下**唯一**的门槛页（2026-11-01 用户定稿）：
 * 中间给两个入口，用户自己选一条——
 * - 上面：全盘访问（更省事、更快，但要去系统设置开特殊权限）；
 * - 下面：选择文件夹（SAF，不用特殊权限，可加多个目录）。
 *
 * 用户一旦走了 SAF，「全盘访问」入口就收进「⋮」管理页（见 [ManageRootsDialog]），
 * 不再占页面中间的位置。
 *
 * **取消授权 / 授权失效都不再切到另一个页面**，只在这页上补一行说明，
 * 避免出现"选择页 → 未获得权限页"两道门（用户反馈：没必要）。
 */
@Composable
private fun PermissionChooserState(
    allFilesSupported: Boolean,
    cancelled: Boolean,
    revoked: Boolean,
    canManage: Boolean,
    onAllFiles: () -> Unit,
    onSaf: () -> Unit,
    onOpenSettings: () -> Unit,
    onManage: () -> Unit,
) {
    val app = LocalAppColors.current
    Box(
        Modifier.fillMaxSize().padding(Dimens.pagePadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "读取本机视频",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "选择一种方式，随时可以切换",
                style = MaterialTheme.typography.bodySmall,
                color = app.text3,
                modifier = Modifier.padding(top = 6.dp, bottom = if (cancelled || revoked) 4.dp else 20.dp),
            )

            // 取消 / 失效的说明就写在这页里（不另开页面）
            if (cancelled || revoked) {
                Text(
                    text = if (revoked) {
                        "已授权的文件夹权限失效了（可能在系统设置里被撤销），重新选一次即可恢复。"
                    } else {
                        "刚才没有完成授权。可以再选一次，或改用另一种方式。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (revoked) MaterialTheme.colorScheme.error else app.text3,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            if (allFilesSupported) {
                ChoiceCard(
                    title = "全盘访问",
                    description = "一次开启即可扫描整台手机（含 Download 等目录），扫描更快。" +
                        "需要你在系统设置里允许「所有文件访问权限」。",
                    actionText = "去开启",
                    primary = true,
                    onClick = onAllFiles,
                )
                Box(Modifier.padding(vertical = 12.dp))
            }

            ChoiceCard(
                title = "选择文件夹",
                description = "自己挑要扫描的目录（如 Movies、DCIM），可添加多个；" +
                    "不需要任何特殊权限。",
                actionText = "选择文件夹",
                primary = !allFilesSupported,
                onClick = onSaf,
            )

            if (revoked || canManage) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (revoked) {
                        AppButton(
                            text = "前往系统设置",
                            onClick = onOpenSettings,
                            style = AppButtonStyle.Text,
                            fillMaxWidth = false,
                            height = 40.dp,
                        )
                    }
                    if (canManage) {
                        AppButton(
                            text = "管理文件夹",
                            onClick = onManage,
                            style = AppButtonStyle.Text,
                            fillMaxWidth = false,
                            height = 40.dp,
                        )
                    }
                }
            }
        }
    }
}

/** 选择页上的一张方案卡：主方案用实心按钮，次方案用描边按钮。 */
@Composable
private fun ChoiceCard(
    title: String,
    description: String,
    actionText: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val app = LocalAppColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = AppShape.card, clip = false)
            .clip(AppShape.card)
            .background(app.surface2)
            .border(1.dp, app.borderStrong, AppShape.card)
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = app.text3,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
        if (primary) {
            AppButton(text = actionText, onClick = onClick)
        } else {
            AppButton(text = actionText, onClick = onClick, style = AppButtonStyle.Outlined)
        }
    }
}

// ---------------------------------------------------------------- 已授权：文件夹 + 视频

@Composable
private fun LocalAuthorized(
    source: LocalMediaSource,
    ignoreStore: LocalIgnoreStore,
    caps: ClientCapabilities,
    onPlay: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onAddRoot: () -> Unit,
    onManage: () -> Unit,
    allFilesAccess: Boolean,
    refreshSignal: Int = 0,
    /** 是否处于本机 tab 的「二级页」（文件夹内）：true 时外层收起悬浮导航。 */
    onSubPageChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var libraries by remember { mutableStateOf<List<MediaLibrary>>(emptyList()) }
    var ignored by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<MediaItemDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // `items` 属于哪个文件夹。**渲染时以这个为准**：切文件夹的那一刻，`LaunchedEffect`
    // 还没跑（副作用在组合之后），只看 `items` 会先画一帧上一个文件夹的内容 —— 2026-11-02 用户反馈。
    var itemsFolder by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    // 排序 / 筛选：状态放在页面这一层，入口在顶栏（与媒体库详情页同款，非老版横条）
    var sort by remember { mutableStateOf(SortSpec()) }
    var filter by remember { mutableStateOf(FilterSpec()) }
    var filterSheetVisible by remember { mutableStateOf(false) }

    // 系统返回键：进了文件夹就退回「文件夹列表」，不要直接把 App 退掉
    // （2026-11-01 用户反馈：进文件夹后按返回直接退出 App）。
    // 列表页不拦：那是 tab 根，交给系统默认行为。
    BackHandler(enabled = selectedFolderId != null) { selectedFolderId = null }

    // 预览图：与媒体库同一套「直放地址抽一帧」逻辑；本机直放地址是 content://，已支持
    val thumbProvider = remember(source, caps) { LocalThumbnailProvider(context, source, caps) }

    // source 变化 = 新增/移除根，库列表必须重扫（多根合并结果变了）；
    // refreshSignal = 「⋮」里恢复忽略 / 重新授权等外部改动（源实例可能没变）
    LaunchedEffect(source, refreshSignal, refreshKey) {
        loading = true
        libraries = source.getLibraries()
        ignored = ignoreStore.ignoredFolders(source.serverId)
        // 移除某根后，原先选中的文件夹可能已不存在 → 退回列表，别停在空网格里
        if (selectedFolderId != null && libraries.none { it.id == selectedFolderId }) {
            selectedFolderId = null
        }
        loading = false
    }

    LaunchedEffect(selectedFolderId, refreshKey) {
        val folder = selectedFolderId
        if (folder == null) {
            // 退回文件夹列表：顺手把条目清掉，下次进别处不会先闪旧内容
            items = emptyList()
            itemsFolder = null
            return@LaunchedEffect
        }
        items = source.getItems(ItemQuery(libraryId = folder, limit = 500)).items
        itemsFolder = folder
    }

    // 进了文件夹 = 本机 tab 的二级页：交给 MainScaffold 收起悬浮导航（与「设置 → 二级页」同一套）
    LaunchedEffect(selectedFolderId) {
        onSubPageChange(selectedFolderId != null)
    }

    Column(Modifier.fillMaxSize()) {
        val app = LocalAppColors.current
        val folderName = selectedFolderId?.let { id -> libraries.firstOrNull { it.id == id }?.name }
        // 顶栏统一走设计系统 PageHeader：返回按钮 = CircleIconButton + BackIcon（与媒体库/设置/关于一致），
        // 右侧动作用同一套 CircleIconButton 包 20dp 图标，不再用「‹」「＋」「⋮」文字字形充当图标
        // （2026-11-01 用户反馈：本机顶栏按钮样式、大小与媒体库不一致）。
        PageHeader(
            title = folderName ?: "本机",
            onBack = if (selectedFolderId != null) ({ selectedFolderId = null }) else null,
            actions = {
                if (selectedFolderId == null && !allFilesAccess) {
                    // 多根：随时追加一个目录（Android 11+ 无法一次授权整台手机）
                    // 全盘档下不显示：那时 SAF 根不参与扫描，加了也不生效（入口收进「⋮」）
                    CircleIconButton(onClick = onAddRoot) { AddIcon(tint = app.text3) }
                }
                // 排序 / 筛选入口：只在「进了文件夹」时出现，位置与视觉对齐媒体库详情页的顶栏图标
                if (selectedFolderId != null) {
                    CircleIconButton(onClick = { filterSheetVisible = true }) {
                        FilterListIcon(tint = app.text3)
                    }
                }
                CircleIconButton(onClick = onManage) { MoreVerticalIcon(tint = app.text3) }
            },
        )

        if (selectedFolderId == null) {
            FolderList(
                libraries = libraries,
                loading = loading,
                ignoredCount = ignored.size,
                onOpen = { selectedFolderId = it },
                onIgnore = { folder ->
                    scope.launch {
                        ignoreStore.ignore(source.serverId, folder)
                        libraries = source.getLibraries()
                        ignored = ignoreStore.ignoredFolders(source.serverId)
                    }
                },
                onAddRoot = onAddRoot,
                showAddRoot = !allFilesAccess,
            )
        } else {
            LocalFolderGrid(
                // 只渲染「属于当前文件夹」的条目：切换瞬间旧列表立即失效，走加载态
                items = if (itemsFolder == selectedFolderId) items else emptyList(),
                sort = sort,
                filter = filter,
                sheetVisible = filterSheetVisible,
                onDismissSheet = { filterSheetVisible = false },
                onSort = { sort = it },
                onFilter = { filter = it },
                onReset = { sort = SortSpec(); filter = FilterSpec() },
                loadImage = { item -> thumbProvider.image(item) },
                onPlay = { item -> onPlay(item.id) },
                onOpenDetail = { item -> onOpenDetail(item.id) },
                loading = itemsFolder != selectedFolderId,
            )
        }
    }
}

@Composable
private fun FolderList(
    libraries: List<MediaLibrary>,
    loading: Boolean,
    onOpen: (String) -> Unit,
    onIgnore: (String) -> Unit,
    onAddRoot: () -> Unit,
    showAddRoot: Boolean = true,
    ignoredCount: Int = 0,
) {
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在扫描本机视频…", color = LocalAppColors.current.text3)
        }
        return
    }
    if (libraries.isEmpty()) {
        Box(
            Modifier.fillMaxSize().padding(Dimens.pagePadding),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                // 「一个都没扫到」有两个完全不同的原因，别让用户看到同一句话猜：
                // 有视频但都被忽略 vs 真的没有视频。忽略时会明说是被忽略 + 去哪儿恢复。
                title = if (ignoredCount > 0) "文件夹都被忽略了" else "没有找到视频",
                description = when {
                    ignoredCount > 0 ->
                        "有 $ignoredCount 个含视频的文件夹被设为忽略，所以这里为空。到右上角「⋮ → 已忽略」里恢复即可。"
                    showAddRoot ->
                        "当前授权的文件夹里还没有视频文件。可以再授权其它目录（如 Movies、DCIM、Download 下的子目录）。"
                    else ->
                        "全盘访问已开启，但没扫到视频文件。确认手机里确实有视频，或到「⋮」改用文件夹方式。"
                },
                actionText = if (showAddRoot) "添加文件夹" else null,
                onAction = onAddRoot,
            )
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Dimens.pagePadding, vertical = Spacing.sm.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm.dp),
    ) {
        items(libraries, key = { it.id }) { lib ->
            FolderRow(
                library = lib,
                onOpen = { onOpen(lib.id) },
                onIgnore = { onIgnore(lib.id.removePrefix("local:")) },
            )
        }
        // 「还能再授权一个目录」必须显式可见：系统不允许一次选整机根，
        // 只靠右上角一个小加号，用户会以为授权一次就到头了（2026-10-31 反馈）。
        if (showAddRoot) {
            item(key = "add-root") {
                AddFolderRow(onClick = onAddRoot)
            }
        }
    }
}

/** 列表末尾的「添加文件夹」行：多根授权的常驻入口。 */
@Composable
private fun AddFolderRow(onClick: () -> Unit) {
    val app = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AppShape.card)
            .border(1.dp, app.borderStrong, AppShape.card)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.pagePadding, vertical = Spacing.md.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AddIcon(tint = app.text3, modifier = Modifier.padding(end = 12.dp).size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "添加文件夹",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "再授权一个目录（Movies、DCIM、Download 子目录等）；多个文件夹会合并显示",
                style = MaterialTheme.typography.bodySmall,
                color = app.text3,
            )
        }
    }
}

/**
 * 文件夹卡片（任务 2）：用**二级面 + 强描边 + 轻阴影**与页面背景拉开层级，
 * 亮 / 暗色下都能看清轮廓（此前用 `surfaceVariant × 0.5` 与背景几乎同色）。
 */
@Composable
private fun FolderRow(
    library: MediaLibrary,
    onOpen: () -> Unit,
    onIgnore: () -> Unit,
) {
    val app = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = AppShape.card, clip = false)
            .clip(AppShape.card)
            .background(app.surface2)
            .border(1.dp, app.borderStrong, AppShape.card)
            .clickable(onClick = onOpen)
            .padding(horizontal = Dimens.pagePadding, vertical = Spacing.md.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                library.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${library.itemCount ?: 0} 个视频",
                style = MaterialTheme.typography.bodySmall,
                color = app.text3,
            )
        }
        AppButton(
            text = "忽略",
            onClick = onIgnore,
            style = AppButtonStyle.Text,
            fillMaxWidth = false,
            height = 40.dp,
        )
    }
}

// ---------------------------------------------------------------- 多根 / 忽略管理（⋮）

/**
 * 「⋮」管理页：当前读取方式 + 已授权文件夹（可移除、失效标记）+ 已忽略文件夹（可恢复）+ 添加文件夹。
 *
 * 读取方式（`docs/12` §3.1）随状态换位：
 * - 用户还没开全盘访问 → 这里给「全盘访问（更快）」入口（首次进页面时它占中间位置，
 *   用户一旦选了 SAF 就收到这个弹窗里，不再挡主界面）；
 * - 已经开了全盘访问 → 显示「当前：全盘访问」+ 关闭方式说明。
 *
 * 放在一个弹窗里而不是新页面：条目通常个位数，弹窗足够且不打断浏览路径。
 */
@Composable
private fun ManageRootsDialog(
    roots: List<LocalRoot>,
    isGranted: (String) -> Boolean,
    ignored: Set<String>,
    allFilesAccess: Boolean,
    allFilesSupported: Boolean,
    onAdd: () -> Unit,
    onRequestAllFiles: () -> Unit,
    onRemove: (LocalRoot) -> Unit,
    onRestoreIgnored: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val app = LocalAppColors.current
    // 列表型弹窗走规范外壳：20dp 圆角、四边 20dp、内容↔按钮 12dp（与 AppConfirmDialog 同口径）
    AppListDialog(title = "本机文件夹", onDismiss = onDismiss) {
        Column(
            Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            // 列表内部节奏 8dp（原来是 4dp，与全站 8/12 节奏不一致）
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 读取方式 ──
            SectionLabel("读取方式", variant = SectionLabelVariant.Compact)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (allFilesAccess) "全盘访问（已开启）" else "按文件夹读取（SAF）",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (allFilesAccess) {
                            "扫描整台手机；要关闭请到系统「所有文件访问权限」里关掉"
                        } else {
                            "只读你授权的目录；开启全盘访问可一次覆盖整台手机，扫描更快"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = app.text3,
                    )
                }
                if (!allFilesAccess && allFilesSupported) {
                    AppButton(
                        text = "去开启",
                        onClick = onRequestAllFiles,
                        style = AppButtonStyle.Text,
                        fillMaxWidth = false,
                        height = 40.dp,
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionLabel("已授权（${roots.size}）", variant = SectionLabelVariant.Compact)
            if (roots.isEmpty()) {
                Text(
                    text = if (allFilesAccess) "全盘访问模式下不需要授权文件夹。" else "还没有授权任何文件夹。",
                    style = MaterialTheme.typography.bodySmall,
                    color = app.text3,
                )
            } else {
                roots.forEach { root ->
                    val ok = isGranted(root.treeUri)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = root.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when {
                                    !ok -> "授权已失效"
                                    allFilesAccess -> "当前未使用（全盘访问优先）"
                                    else -> "根目录：${root.alias.ifBlank { "内部存储" }}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ok) app.text3 else MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        AppButton(
                            text = "移除",
                            onClick = { onRemove(root) },
                            style = AppButtonStyle.Text,
                            destructive = true,
                            fillMaxWidth = false,
                            height = 40.dp,
                        )
                    }
                }
            }
            if (!allFilesAccess) {
                AppButton(
                    text = "添加文件夹",
                    onClick = onAdd,
                    style = AppButtonStyle.Text,
                    fillMaxWidth = false,
                    height = 40.dp,
                    leadingIcon = {
                        AddIcon(tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionLabel("已忽略（${ignored.size}）", variant = SectionLabelVariant.Compact)
            if (ignored.isEmpty()) {
                Text("还没有忽略任何文件夹。", style = MaterialTheme.typography.bodySmall, color = app.text3)
            } else {
                ignored.sorted().forEach { folder ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = folder.ifBlank { "本机根目录" },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        AppButton(
                            text = "恢复",
                            onClick = { onRestoreIgnored(folder) },
                            style = AppButtonStyle.Text,
                            fillMaxWidth = false,
                            height = 40.dp,
                        )
                    }
                }
            }
        }
    }
}


// ---------------------------------------------------------------- 系统设置跳转

/** 「前往系统设置」：打开本应用的系统设置页（SAF 没有全局开关，这里是合法且诚实的落脚点）。 */
private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
