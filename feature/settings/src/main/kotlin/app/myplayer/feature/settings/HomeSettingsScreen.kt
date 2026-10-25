package app.myplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.myplayer.core.datastore.HomeModuleSettings
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalBottomNavSpace
import app.myplayer.core.designsystem.MyPlayerTheme
import app.myplayer.core.designsystem.Spacing
import app.myplayer.core.designsystem.components.PageHeader
import app.myplayer.core.designsystem.components.SettingsGroup
import app.myplayer.core.designsystem.components.SettingsMenuRow
import app.myplayer.core.designsystem.components.SettingsOption
import app.myplayer.core.designsystem.components.SettingsSwitchRow

/** 当前页面正在编辑哪一套首页配置（会话内 UI 状态，不持久化 —— 重进页面回到 Emby）。 */
enum class HomeConfigTarget { EMBY, SMB }

/** 配置对象下拉的选项。 */
private val TargetOptions = listOf(
    SettingsOption(HomeConfigTarget.EMBY, "Emby"),
    SettingsOption(HomeConfigTarget.SMB, "SMB"),
)

/** 最近添加显示数量的选项（条）。 */
val RecentlyAddedLimitOptions = listOf(10, 20, 30)

/** 最近添加时间范围的选项（天）。 */
val RecentlyAddedDaysOptions = listOf(3, 7, 15, 30)

/** 模块显示控制的一行：标题 + 副标题随配置对象切换。 */
data class HomeModuleRow(val title: String, val description: String)

/**
 * 四个模块开关的文案（`docs/03` §7 首页设置）。
 *
 * 抽成纯函数是为了能被单测钉住 —— 文案写错目标（SMB 的副标题里出现「服务器」），
 * 只有逐字看才能发现，编译器和 UI 测试都不会报错。
 */
internal fun moduleRows(target: HomeConfigTarget): List<HomeModuleRow> = when (target) {
    HomeConfigTarget.EMBY -> listOf(
        HomeModuleRow("继续观看", "展示服务器未看完的视频"),
        HomeModuleRow("我的媒体库", "展示服务器的库入口"),
        HomeModuleRow("媒体库分类动态展示", "按库展示最新内容"),
        HomeModuleRow("最近添加", "展示最新入库的视频"),
    )
    HomeConfigTarget.SMB -> listOf(
        HomeModuleRow("继续观看", "展示本设备未看完的视频"),
        HomeModuleRow("我的文件夹", "展示扫描到的非空文件夹"),
        HomeModuleRow("文件夹内容动态展示", "按文件夹展示最新文件"),
        HomeModuleRow("最近添加", "展示最新修改的视频文件"),
    )
}

/**
 * 首页设置二级页（设置 → 首页设置）。
 *
 * **一个页面、两套配置**：Emby 与 SMB 的首页模块规则互相独立（[HomeModuleSettings]），
 * 顶部「配置对象」决定下方所有开关 / 下拉读写哪一套 —— 切换目标时整组状态立即跟着换，
 * 每次修改也只写回当前目标的那一份，另一套不受影响。
 *
 * 结构（从上到下）：
 * 1. 配置对象（下拉：Emby / SMB）
 * 2. 模块显示控制（4 个开关，文案随目标切换）
 * 3. 最近添加的特殊设置（数量 / 时间范围，受该目标的「最近添加」开关控制，关闭时置灰）
 *
 * 状态策略与字幕设置一致：改动即时通过 [onEmbyChange] / [onSmbChange] 写回
 * `SettingsStore`（整份 JSON 落盘），无「保存」按钮；开关值以 Store 回流为准。
 *
 * @param emby Emby 模式的首页规则
 * @param smb SMB 模式的首页规则
 * @param onEmbyChange 写回 Emby 配置（整份替换）
 * @param onSmbChange 写回 SMB 配置（整份替换）
 * @param initialTarget 进入页面时选中哪个配置对象（生产用默认 Emby；SMB 预览传 SMB）
 */
@Composable
fun HomeSettingsScreen(
    emby: HomeModuleSettings,
    smb: HomeModuleSettings,
    onEmbyChange: (HomeModuleSettings) -> Unit,
    onSmbChange: (HomeModuleSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialTarget: HomeConfigTarget = HomeConfigTarget.EMBY,
) {
    // 当前正在编辑哪一套：会话内状态，rememberSaveable 保证旋转 / 进程恢复不跳回旧目标
    var target by rememberSaveable { mutableStateOf(initialTarget) }

    /** 当前目标的配置（唯一事实来源，切换目标 = 整组状态立即换）。 */
    val current = if (target == HomeConfigTarget.EMBY) emby else smb

    /** 写回**当前目标**的配置 —— 另一套永远不被这次修改碰到。 */
    fun update(transform: (HomeModuleSettings) -> HomeModuleSettings) {
        if (target == HomeConfigTarget.EMBY) onEmbyChange(transform(emby)) else onSmbChange(transform(smb))
    }

    val rows = moduleRows(target)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimens.pagePadding,
            end = Dimens.pagePadding,
            top = Spacing.sm.dp,
            // 悬浮导航高度（含手势条 + 呼吸）：末尾卡片不被胶囊压住
            bottom = LocalBottomNavSpace.current,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl.dp),
    ) {
        // ── 分组 1：配置目标选择 ──
        item(key = "target") {
            SettingsGroup(title = "配置对象") {
                SettingsMenuRow(
                    title = "配置对象",
                    description = "分别配置 Emby 与 SMB 的首页规则",
                    options = TargetOptions,
                    selected = target,
                    onSelect = { target = it },
                    showDivider = false,
                )
            }
        }

        // ── 分组 2：模块显示控制（4 个开关，状态随目标切换） ──
        // key 里带上 target：切换目标时行内容（标题/副标题/开关值）作为新 item 重组，
        // 避免 Compose 复用旧行时开关动画播错方向。
        item(key = "modules-$target") {
            SettingsGroup(title = "模块显示控制") {
                rows.forEachIndexed { index, row ->
                    SettingsSwitchRow(
                        title = row.title,
                        description = row.description,
                        checked = when (index) {
                            0 -> current.showContinueWatching
                            1 -> current.showLibraryEntries
                            2 -> current.showDynamicSections
                            else -> current.showRecentlyAdded
                        },
                        onCheckedChange = { v ->
                            update {
                                when (index) {
                                    0 -> it.copy(showContinueWatching = v)
                                    1 -> it.copy(showLibraryEntries = v)
                                    2 -> it.copy(showDynamicSections = v)
                                    else -> it.copy(showRecentlyAdded = v)
                                }
                            }
                        },
                        // 最后一行显式关掉分隔线：否则卡片底部多出一条贴边线
                        showDivider = index < rows.lastIndex,
                    )
                }
            }
        }

        // ── 分组 3：最近添加的特殊设置 ──
        // 受**当前目标**的 showRecentlyAdded 控制：关掉即置灰（值保留，重开不丢档位）。
        item(key = "recently-added-$target") {
            SettingsGroup(title = "最近添加") {
                SettingsMenuRow(
                    title = "显示数量",
                    description = "最多展示多少条",
                    options = RecentlyAddedLimitOptions.map { SettingsOption(it, "$it 条") },
                    selected = current.recentlyAddedLimit,
                    onSelect = { v -> update { it.copy(recentlyAddedLimit = v) } },
                    enabled = current.showRecentlyAdded,
                )
                SettingsMenuRow(
                    title = "时间范围",
                    description = "只统计最近几天内入库 / 修改的内容",
                    options = RecentlyAddedDaysOptions.map { SettingsOption(it, "$it 天") },
                    selected = current.recentlyAddedDays,
                    onSelect = { v -> update { it.copy(recentlyAddedDays = v) } },
                    enabled = current.showRecentlyAdded,
                    showDivider = false,
                )
            }
        }
    }
}

/**
 * 带顶部栏的完整页面（AppNavHost 接的就是它；@Preview 也用它对齐真实版式）。
 *
 * 顶栏 + LazyColumn 分层包：`LazyColumn` 的 contentPadding 不含顶栏高度，
 * 页面滚动时顶栏保持固定（与设置主页的 PageHeader 同款行为）。
 */
@Composable
fun HomeSettingsPage(
    emby: HomeModuleSettings,
    smb: HomeModuleSettings,
    onEmbyChange: (HomeModuleSettings) -> Unit,
    onSmbChange: (HomeModuleSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialTarget: HomeConfigTarget = HomeConfigTarget.EMBY,
) {
    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = "首页设置",
            subtitle = "按数据源分别配置各模块",
            onBack = onBack,
        )
        HomeSettingsScreen(
            emby = emby,
            smb = smb,
            onEmbyChange = onEmbyChange,
            onSmbChange = onSmbChange,
            onBack = onBack,
            initialTarget = initialTarget,
        )
    }
}

// ---- @Preview ----

/** Emby 模式（默认进入的形态）：全部开关默认开，数量 20 条 / 7 天。 */
@Preview(name = "首页设置 · Emby · 暗色", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 900)
@Composable
private fun HomeSettingsEmbyPreview() {
    MyPlayerTheme(darkTheme = true) {
        HomeSettingsPage(
            emby = HomeModuleSettings(),
            smb = HomeModuleSettings(),
            onEmbyChange = {},
            onSmbChange = {},
            onBack = {},
        )
    }
}

/**
 * SMB 模式：文案换成文件夹语义；smb 配置故意把「最近添加」关掉，
 * 验证第三组（显示数量 / 时间范围）随之置灰。
 */
@Preview(name = "首页设置 · SMB · 暗色", showBackground = true, backgroundColor = 0xFF0B0D11, heightDp = 900)
@Composable
private fun HomeSettingsSmbPreview() {
    MyPlayerTheme(darkTheme = true) {
        HomeSettingsPage(
            emby = HomeModuleSettings(),
            smb = HomeModuleSettings(showRecentlyAdded = false),
            onEmbyChange = {},
            onSmbChange = {},
            onBack = {},
            initialTarget = HomeConfigTarget.SMB,
        )
    }
}

@Preview(name = "首页设置 · 亮色", showBackground = true, backgroundColor = 0xFFF4F6F9, heightDp = 900)
@Composable
private fun HomeSettingsLightPreview() {
    MyPlayerTheme(darkTheme = false) {
        HomeSettingsPage(
            emby = HomeModuleSettings(),
            smb = HomeModuleSettings(),
            onEmbyChange = {},
            onSmbChange = {},
            onBack = {},
        )
    }
}
