package app.myplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import app.myplayer.core.datastore.AspectRatio
import app.myplayer.core.datastore.LibraryDisplayMode
import app.myplayer.core.datastore.PlayerSettings
import app.myplayer.core.datastore.ScreenOrientation
import app.myplayer.core.datastore.ServerConfig
import app.myplayer.core.datastore.SubtitleStyle
import app.myplayer.core.datastore.ThemeMode
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.LocalBottomNavSpace
import app.myplayer.core.designsystem.MyPlayerTheme
import app.myplayer.core.designsystem.Spacing
import app.myplayer.core.designsystem.components.CircleIconButton
import app.myplayer.core.designsystem.components.MoreVerticalIcon
import app.myplayer.core.designsystem.components.PageHeader
import app.myplayer.core.designsystem.components.ServerIcon
import app.myplayer.core.designsystem.components.SettingsGroup
import app.myplayer.core.designsystem.components.SettingsMenuRow
import app.myplayer.core.designsystem.components.SettingsNavRow
import app.myplayer.core.designsystem.components.SettingsOption
import app.myplayer.core.designsystem.components.SettingsRow
import app.myplayer.core.designsystem.components.SettingsSwitchRow
import app.myplayer.core.model.SourceType

/**
 * 设置页 —— 「分组列表 + 下拉菜单」（`docs/03-UI设计规范.md` §7）。
 *
 * 版式规则（本次重构的硬约束）：
 * 1. 整页只有一个 [LazyColumn]，每个分组由 [SettingsGroup] 包成 12dp 圆角 `surface` 卡片；
 * 2. **页面上不存在任何平铺的 Chip / 选项按钮** —— 所有枚举型设置都收进
 *    [SettingsMenuRow] 的 `DropdownMenu`，行上只留「当前值 + 向下箭头」；
 * 3. 行高统一 [Dimens.rowMinHeight]（56dp），行间 1dp 发丝线（左缩进 16dp）；
 * 4. 改动即时写回 `SettingsStore`，无「保存」按钮。
 *
 * 三种行形态：
 * - A 开关型 → [SettingsSwitchRow]
 * - B 下拉型 → [SettingsMenuRow]（本页主体）
 * - C 跳转型 → [SettingsNavRow]（字幕 → 二级页）
 *
 * 字幕的字号 / 描边 / 颜色 / 位置是**连续值 + 实时预览**，平铺在主页既占空间又难调，
 * 因此统一收进 [SubtitleSettingsScreen]，本页只保留一行入口。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSetActiveServer: (String) -> Unit,
    onEditServer: (String) -> Unit,
    onDeleteServer: (String) -> Unit,
    onAddServer: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDefaultSpeed: (Float) -> Unit,
    onKeepBackgroundPlayback: (Boolean) -> Unit,
    onDoubleTapSeek: (Long) -> Unit,
    onAutoPlayNext: (Boolean) -> Unit,
    onScreenOrientation: (ScreenOrientation) -> Unit,
    onDefaultAspectRatio: (AspectRatio) -> Unit,
    onSubtitleSettings: () -> Unit,
    onAppLockEnabled: (Boolean) -> Unit,
    onUnlockGrace: (Long) -> Unit,
    onHideRecentsSnapshot: (Boolean) -> Unit,
    onLocalThumbnails: (Boolean) -> Unit,
    onSearchFuzzyPinyin: (Boolean) -> Unit,
    onSearchTraditionalSimplified: (Boolean) -> Unit,
    onAllowCleartextHttp: (Boolean) -> Unit,
    onTrustSelfSignedCert: (Boolean) -> Unit,
    onLibraryDisplayMode: (LibraryDisplayMode) -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val st = state.settings

    Column(modifier.fillMaxSize()) {
        // 设置是 tab 页（无上级页面）→ 不画返回箭头。
        // [onBack] 仍保留在签名里给 AppNavHost 复用，传 `null` 时 PageHeader 只渲染标题。
        PageHeader(title = "设置", subtitle = "改动即时生效", onBack = null)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Dimens.pagePadding,
                end = Dimens.pagePadding,
                top = Spacing.sm.dp,
                // 悬浮导航高度（含手势条 + 呼吸）：否则最后一张卡片会被胶囊压住
                bottom = LocalBottomNavSpace.current,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl.dp),
        ) {
            // ── 服务器 ──
            item {
                ServerGroup(
                    servers = state.servers,
                    onSetActive = onSetActiveServer,
                    onEdit = onEditServer,
                    onDelete = onDeleteServer,
                    onAdd = onAddServer,
                )
            }

            // ── 外观 ──
            item {
                SettingsGroup(title = "外观") {
                    SettingsMenuRow(
                        title = "外观主题",
                        description = "跟随系统时随系统深浅色自动切换",
                        options = ThemeOptions,
                        selected = st.themeMode,
                        onSelect = onThemeMode,
                    )
                    SettingsMenuRow(
                        title = "库展示方式",
                        options = DisplayModeOptions,
                        selected = st.libraryDisplayMode,
                        onSelect = onLibraryDisplayMode,
                        showDivider = false,
                    )
                }
            }

            // ── 播放 ──
            item {
                SettingsGroup(title = "播放") {
                    SettingsSwitchRow(
                        title = "保持后台播放",
                        description = "切到后台不暂停；关闭后切走即暂停",
                        checked = st.keepBackgroundPlayback,
                        onCheckedChange = onKeepBackgroundPlayback,
                    )
                    SettingsMenuRow(
                        title = "默认倍速",
                        description = "每次起播的初始播放速度",
                        options = speedOptionsFor(st.defaultSpeed),
                        selected = st.defaultSpeed,
                        onSelect = onDefaultSpeed,
                    )
                    SettingsMenuRow(
                        title = "双击快进",
                        description = "播放页双击画面左右两侧的跳转时长",
                        options = SeekOptions,
                        selected = st.doubleTapSeekMillis,
                        onSelect = onDoubleTapSeek,
                    )
                    SettingsSwitchRow(
                        title = "自动连播",
                        description = "播完自动播放下一集",
                        checked = st.autoPlayNext,
                        onCheckedChange = onAutoPlayNext,
                        showDivider = false,
                    )
                }
            }

            // ── 画面 ──
            item {
                SettingsGroup(title = "画面") {
                    SettingsMenuRow(
                        title = "屏幕方向",
                        options = OrientationOptions,
                        selected = st.screenOrientation,
                        onSelect = onScreenOrientation,
                    )
                    SettingsMenuRow(
                        title = "画面比例",
                        options = AspectOptions,
                        selected = st.defaultAspectRatio,
                        onSelect = onDefaultAspectRatio,
                        showDivider = false,
                    )
                }
            }

            // ── 字幕 ──
            item {
                SettingsGroup(title = "字幕") {
                    // 一级页只留入口：字号 / 描边 / 颜色 / 位置统一收进二级页，
                    // 那里有实时预览，调参时不用来回翻页。
                    SettingsNavRow(
                        title = "字幕设置",
                        description = "字体、颜色与位置",
                        onClick = onSubtitleSettings,
                        showDivider = false,
                    )
                }
            }

            // ── 隐私 ──
            item {
                SettingsGroup(title = "隐私") {
                    SettingsSwitchRow(
                        title = "应用锁",
                        description = "回到前台需要图案解锁",
                        checked = st.appLockEnabled,
                        onCheckedChange = onAppLockEnabled,
                    )
                    if (st.appLockEnabled) {
                        SettingsMenuRow(
                            title = "解锁宽限期",
                            description = "离开多久之内回来不用重新解锁",
                            options = GraceOptions,
                            selected = st.unlockGraceMillis,
                            onSelect = onUnlockGrace,
                        )
                    }
                    SettingsSwitchRow(
                        title = "遮挡任务快照",
                        description = "最近任务列表里隐藏播放内容",
                        checked = st.hideRecentsSnapshot,
                        onCheckedChange = onHideRecentsSnapshot,
                        showDivider = false,
                    )
                }
            }

            // ── 预览 ──
            item {
                SettingsGroup(title = "预览") {
                    SettingsSwitchRow(
                        title = "本地生成缩略图",
                        description = "服务端无海报时从视频抽帧（部分源不支持）",
                        checked = st.localThumbnails,
                        onCheckedChange = onLocalThumbnails,
                        showDivider = false,
                    )
                }
            }

            // ── 搜索 ──
            item {
                SettingsGroup(title = "搜索") {
                    SettingsSwitchRow(
                        title = "模糊拼音",
                        description = "zh / ch、an / ang 等模糊匹配",
                        checked = st.searchFuzzyPinyin,
                        onCheckedChange = onSearchFuzzyPinyin,
                    )
                    SettingsSwitchRow(
                        title = "繁简互搜",
                        description = "用简体也能搜到繁体标题",
                        checked = st.searchTraditionalSimplified,
                        onCheckedChange = onSearchTraditionalSimplified,
                        showDivider = false,
                    )
                }
            }

            // ── 网络 ──
            item {
                SettingsGroup(title = "网络") {
                    SettingsSwitchRow(
                        title = "明文 HTTP",
                        description = "允许 http:// 服务器（家庭内网通常需要开）",
                        checked = st.allowCleartextHttp,
                        onCheckedChange = onAllowCleartextHttp,
                    )
                    SettingsSwitchRow(
                        title = "信任自签证书",
                        description = "HTTPS 服务器使用自签证书时开启",
                        checked = st.trustSelfSignedCert,
                        onCheckedChange = onTrustSelfSignedCert,
                        showDivider = false,
                    )
                }
            }

            // ── 关于 ──
            item {
                SettingsGroup(title = "关于") {
                    SettingsNavRow(
                        title = "关于 LimePlayer",
                        description = "版本 0.1.0 · 开源许可声明",
                        onClick = onAbout,
                        showDivider = false,
                    )
                }
            }
        }
    }
}

// ---- 服务器分组 ----

/**
 * 服务器分组：每台服务器一行（图标 + 名称 + 地址），行尾 `⋯` 打开操作菜单
 * （修改 / 设为当前 / 删除）。当前服务器行显示品牌色「当前」。
 */
@Composable
private fun ServerGroup(
    servers: List<ServerRow>,
    onSetActive: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var pendingDelete by remember { mutableStateOf<ServerRow?>(null) }

    SettingsGroup(title = "服务器") {
        if (servers.isEmpty()) {
            SettingsRow(
                title = "还没有添加服务器",
                description = "添加后即可浏览媒体库",
            )
        }
        servers.forEach { row ->
            ServerRowItem(
                row = row,
                onSetActive = onSetActive,
                onEdit = { onEdit(row.config.id) },
                onRequestDelete = { pendingDelete = row },
            )
        }
        SettingsNavRow(
            title = "添加服务器",
            description = "Emby / Jellyfin / 飞牛影视",
            onClick = onAdd,
            showDivider = false,
        )
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除服务器") },
            text = { Text("删除「${row.config.name}」？其登录凭据会一并清除，媒体库缓存保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(row.config.id)
                        pendingDelete = null
                    },
                ) {
                    Text("删除", color = scheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
            // 弹层同样 12dp 圆角，禁止胶囊
            shape = AppShape.card,
            containerColor = scheme.surface,
        )
    }
}

@Composable
private fun ServerRowItem(
    row: ServerRow,
    onSetActive: (String) -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val host = row.config.baseUrl.removePrefix("http://").removePrefix("https://")

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val menuWidth = 168.dp
        val offsetX = (maxWidth - menuWidth).coerceAtLeast(0.dp)

        SettingsRow(
            title = row.config.name,
            description = "$host · ${row.config.sourceType.name.lowercase()}",
            onClick = { if (!row.active) onSetActive(row.config.id) },
            leading = {
                ServerIcon(
                    tint = if (row.active) scheme.primary else app.text3,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailing = {
                if (row.active) {
                    Text(
                        text = "当前",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.primary,
                    )
                    Spacer(Modifier.width(Spacing.xs.dp))
                }
                CircleIconButton(onClick = { menuOpen = true }) {
                    MoreVerticalIcon(tint = app.text3, modifier = Modifier.size(18.dp))
                }
            },
        )

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.width(menuWidth),
            offset = DpOffset(x = offsetX, y = 0.dp),
            shape = AppShape.card,
            containerColor = scheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(Dimens.hairline, app.border),
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = "修改服务器",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                },
                onClick = {
                    menuOpen = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (row.active) "已是当前服务器" else "设为当前",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (row.active) app.text3 else scheme.onSurface,
                    )
                },
                enabled = !row.active,
                onClick = {
                    menuOpen = false
                    onSetActive(row.config.id)
                },
            )
            HorizontalDivider(color = app.border)
            DropdownMenuItem(
                text = {
                    Text(
                        text = "删除服务器",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.error,
                    )
                },
                onClick = {
                    menuOpen = false
                    onRequestDelete()
                },
            )
        }
    }
}

// ---- 选项表（顶层常量，避免每次重组重新分配） ----

private val ThemeOptions = listOf(
    SettingsOption(ThemeMode.SYSTEM, "跟随系统"),
    SettingsOption(ThemeMode.LIGHT, "亮色"),
    SettingsOption(ThemeMode.DARK, "暗色"),
)

private val DisplayModeOptions = listOf(
    SettingsOption(LibraryDisplayMode.AUTO, "自动"),
    SettingsOption(LibraryDisplayMode.TABS, "页签"),
    SettingsOption(LibraryDisplayMode.DRAWER, "抽屉"),
    SettingsOption(LibraryDisplayMode.AGGREGATE, "聚合"),
)

private val SeekOptions = listOf(
    SettingsOption(5_000L, "5 秒"),
    SettingsOption(10_000L, "10 秒"),
    SettingsOption(30_000L, "30 秒"),
    SettingsOption(60_000L, "60 秒"),
)

private val OrientationOptions = listOf(
    SettingsOption(ScreenOrientation.AUTO, "自动"),
    SettingsOption(ScreenOrientation.LANDSCAPE, "横屏"),
    SettingsOption(ScreenOrientation.PORTRAIT, "竖屏"),
)

private val AspectOptions = listOf(
    SettingsOption(AspectRatio.FIT, "适应画面"),
    SettingsOption(AspectRatio.FILL, "拉伸填充"),
    SettingsOption(AspectRatio.CROP, "裁剪铺满"),
    SettingsOption(AspectRatio.ORIGINAL, "原始尺寸"),
)

private val GraceOptions = listOf(
    SettingsOption(0L, "立即"),
    SettingsOption(60_000L, "1 分钟"),
    SettingsOption(300_000L, "5 分钟"),
)

/** 倍速档位（用户要求 8 档：0.75x ~ 3x）。 */
private val SpeedValues = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

private fun speedLabel(v: Float): String =
    if (v % 1f == 0f) "%.1f×".format(v) else "%.2f×".format(v)

/**
 * 倍速选项；若存量数据是列表外的值（旧版本 1.1x 之类），补一条进去，
 * 保证行上「当前值」永远有字，不会出现空白。
 */
private fun speedOptionsFor(current: Float): List<SettingsOption<Float>> {
    val base = SpeedValues.map { SettingsOption(it, speedLabel(it)) }
    return if (base.any { it.value == current }) {
        base
    } else {
        base + SettingsOption(current, speedLabel(current))
    }
}

// ---- @Preview ----

/**
 * 预览用示例数据：两台服务器（一活跃）+ 默认设置。
 *
 * `@Preview` 不接 ViewModel —— 直接喂 [SettingsUiState]，所有回调都是空实现，
 * 方便在 Android Studio 里单独调设置页版式。
 */
private fun previewState(): SettingsUiState = SettingsUiState(
    servers = listOf(
        ServerRow(
            config = ServerConfig(
                id = "emby-1",
                sourceType = SourceType.EMBY,
                name = "客厅 Emby",
                baseUrl = "http://192.168.31.15:8096",
            ),
            active = true,
        ),
        ServerRow(
            config = ServerConfig(
                id = "fnos-1",
                sourceType = SourceType.FNOS,
                name = "飞牛影视",
                baseUrl = "http://192.168.31.16:5666",
            ),
            active = false,
        ),
    ),
    settings = PlayerSettings(
        themeMode = ThemeMode.SYSTEM,
        defaultSpeed = 1.25f,
        keepBackgroundPlayback = false,
        doubleTapSeekMillis = 10_000L,
        autoPlayNext = true,
        screenOrientation = ScreenOrientation.AUTO,
        defaultAspectRatio = AspectRatio.FIT,
        subtitleStyle = SubtitleStyle(),
        appLockEnabled = true,
        unlockGraceMillis = 0L,
        hideRecentsSnapshot = false,
        allowCleartextHttp = true,
        trustSelfSignedCert = false,
        localThumbnails = true,
        searchFuzzyPinyin = false,
        searchTraditionalSimplified = false,
        libraryDisplayMode = LibraryDisplayMode.AUTO,
    ),
)

@Composable
private fun SettingsScreenPreviewContent(darkTheme: Boolean) {
    MyPlayerTheme(darkTheme = darkTheme) {
        SettingsScreen(
            state = previewState(),
            onSetActiveServer = {},
            onEditServer = {},
            onDeleteServer = {},
            onAddServer = {},
            onThemeMode = {},
            onDefaultSpeed = {},
            onKeepBackgroundPlayback = {},
            onDoubleTapSeek = {},
            onAutoPlayNext = {},
            onScreenOrientation = {},
            onDefaultAspectRatio = {},
            onSubtitleSettings = {},
            onAppLockEnabled = {},
            onUnlockGrace = {},
            onHideRecentsSnapshot = {},
            onLocalThumbnails = {},
            onSearchFuzzyPinyin = {},
            onSearchTraditionalSimplified = {},
            onAllowCleartextHttp = {},
            onTrustSelfSignedCert = {},
            onLibraryDisplayMode = {},
            onAbout = {},
            onBack = {},
        )
    }
}

/** 暗色主题（App 默认）。 */
@Preview(name = "设置 · 暗色", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 900)
@Composable
private fun SettingsScreenDarkPreview() {
    SettingsScreenPreviewContent(darkTheme = true)
}

/** 亮色主题。 */
@Preview(name = "设置 · 亮色", showBackground = true, backgroundColor = 0xFFF4F6F9, heightDp = 900)
@Composable
private fun SettingsScreenLightPreview() {
    SettingsScreenPreviewContent(darkTheme = false)
}
