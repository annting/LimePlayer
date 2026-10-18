package app.myplayer.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.myplayer.core.datastore.AspectRatio
import app.myplayer.core.datastore.LibraryDisplayMode
import app.myplayer.core.datastore.ScreenOrientation
import app.myplayer.core.datastore.ThemeMode
import app.myplayer.core.designsystem.AppRadius
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.components.AppButton
import app.myplayer.core.designsystem.components.AppButtonStyle
import app.myplayer.core.designsystem.components.PageHeader
import app.myplayer.core.designsystem.components.ServerIcon

/**
 * 设置页（`01` §6 / `03` §7）：按分组渲染全部设置项；顶部是**服务器管理**（切换 / 删除 / 添加）。
 * 每个控件改动即写回 `SettingsStore`，无「保存」按钮（即时生效，规范口径）。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSetActiveServer: (String) -> Unit,
    onDeleteServer: (String) -> Unit,
    onAddServer: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDefaultSpeed: (Float) -> Unit,
    onKeepBackgroundPlayback: (Boolean) -> Unit,
    onDoubleTapSeek: (Long) -> Unit,
    onAutoPlayNext: (Boolean) -> Unit,
    onScreenOrientation: (ScreenOrientation) -> Unit,
    onDefaultAspectRatio: (AspectRatio) -> Unit,
    onSubtitleStyle: ((app.myplayer.core.datastore.SubtitleStyle) -> app.myplayer.core.datastore.SubtitleStyle) -> Unit,
    onAppLockEnabled: (Boolean) -> Unit,
    onUnlockGrace: (Long) -> Unit,
    onHideRecentsSnapshot: (Boolean) -> Unit,
    onLocalThumbnails: (Boolean) -> Unit,
    onSearchFuzzyPinyin: (Boolean) -> Unit,
    onSearchTraditionalSimplified: (Boolean) -> Unit,
    onAllowCleartextHttp: (Boolean) -> Unit,
    onTrustSelfSignedCert: (Boolean) -> Unit,
    onLibraryDisplayMode: (LibraryDisplayMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val st = state.settings
    Column(modifier.fillMaxSize()) {
        PageHeader(title = "设置", subtitle = "改动即时生效", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServerSection(
                servers = state.servers,
                onSetActive = onSetActiveServer,
                onDelete = onDeleteServer,
                onAdd = onAddServer,
            )

            SectionTitle("外观")
            ChipRow(
                label = "主题",
                options = listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "亮色", ThemeMode.DARK to "暗色"),
                selected = st.themeMode,
                onSelect = onThemeMode,
            )

            SectionTitle("播放")
            ChipRow(
                label = "默认倍速",
                options = listOf(0.75f to "0.75×", 1f to "1.0×", 1.25f to "1.25×", 1.5f to "1.5×", 2f to "2.0×"),
                selected = st.defaultSpeed,
                onSelect = onDefaultSpeed,
            )
            SwitchRow("保持后台播放", "切后台不暂停（默认关：切走自动暂停）", st.keepBackgroundPlayback, onKeepBackgroundPlayback)
            ChipRow(
                label = "双击快进",
                options = listOf(5_000L to "5s", 10_000L to "10s", 30_000L to "30s", 60_000L to "60s"),
                selected = st.doubleTapSeekMillis,
                onSelect = onDoubleTapSeek,
            )
            SwitchRow("自动连播", "播完自动播下一集", st.autoPlayNext, onAutoPlayNext)

            SectionTitle("画面")
            ChipRow(
                label = "屏幕方向",
                options = listOf(
                    ScreenOrientation.AUTO to "自动",
                    ScreenOrientation.LANDSCAPE to "横屏",
                    ScreenOrientation.PORTRAIT to "竖屏",
                ),
                selected = st.screenOrientation,
                onSelect = onScreenOrientation,
            )
            ChipRow(
                label = "画面比例",
                options = listOf(
                    AspectRatio.FIT to "适应",
                    AspectRatio.FILL to "填充",
                    AspectRatio.CROP to "裁剪",
                    AspectRatio.ORIGINAL to "原始",
                ),
                selected = st.defaultAspectRatio,
                onSelect = onDefaultAspectRatio,
            )

            SectionTitle("字幕")
            SubtitleSection(st.subtitleStyle, onSubtitleStyle)

            SectionTitle("隐私")
            SwitchRow("应用锁", "回前台需要图案解锁", st.appLockEnabled, onAppLockEnabled)
            if (st.appLockEnabled) {
                ChipRow(
                    label = "解锁宽限期",
                    options = listOf(0L to "立即", 60_000L to "1 分钟", 300_000L to "5 分钟"),
                    selected = st.unlockGraceMillis,
                    onSelect = onUnlockGrace,
                )
            }
            SwitchRow("后台遮挡任务快照", "最近任务里不显示应用内容", st.hideRecentsSnapshot, onHideRecentsSnapshot)

            SectionTitle("预览")
            SwitchRow("本地生成缩略图", "服务端无海报时从视频抽帧（部分源不支持）", st.localThumbnails, onLocalThumbnails)

            SectionTitle("搜索")
            SwitchRow("模糊拼音", "zh/ch、an/ang 等模糊匹配", st.searchFuzzyPinyin, onSearchFuzzyPinyin)
            SwitchRow("繁简互搜", "用简体也能搜到繁体标题", st.searchTraditionalSimplified, onSearchTraditionalSimplified)

            SectionTitle("网络")
            SwitchRow("明文 HTTP", "允许 http:// 服务器（家庭内网一般需要开）", st.allowCleartextHttp, onAllowCleartextHttp)
            SwitchRow("信任自签证书", "HTTPS 服务器使用自签证书时开启", st.trustSelfSignedCert, onTrustSelfSignedCert)

            SectionTitle("界面")
            ChipRow(
                label = "库展示方式",
                options = listOf(
                    LibraryDisplayMode.AUTO to "自动",
                    LibraryDisplayMode.TABS to "页签",
                    LibraryDisplayMode.DRAWER to "抽屉",
                    LibraryDisplayMode.AGGREGATE to "聚合",
                ),
                selected = st.libraryDisplayMode,
                onSelect = onLibraryDisplayMode,
            )

            SectionTitle("关于")
            Text(
                "LimePlayer 0.1.0 · 基于 ExoPlayer（Apache-2.0）与 FFmpeg（LGPL-2.1+）构建。" +
                    "本应用不含 FFmpeg 二进制；许可证全文见项目仓库 LICENSES/。",
                style = MaterialTheme.typography.labelSmall,
                color = LocalAppColors.current.text3,
            )
        }
    }
}

// ---- 分区块 ----

@Composable
private fun ServerSection(
    servers: List<ServerRow>,
    onSetActive: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var pendingDelete by remember { mutableStateOf<ServerRow?>(null) }

    SectionTitle("服务器")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (servers.isEmpty()) {
            Text(
                "还没有添加服务器",
                style = MaterialTheme.typography.bodySmall,
                color = LocalAppColors.current.text3,
            )
        }
        servers.forEach { row ->
            val c = row.config
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (row.active) scheme.primaryContainer.copy(alpha = 0.35f) else scheme.surface)
                    .border(
                        1.dp,
                        if (row.active) scheme.primary else scheme.outlineVariant,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable { onSetActive(c.id) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ServerIcon(tint = scheme.primary, modifier = Modifier.size(22.dp))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                    Text(
                        "${c.baseUrl.removePrefix("http://").removePrefix("https://")} · ${c.sourceType.name.lowercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAppColors.current.text3,
                    )
                }
                if (row.active) {
                    Text("当前", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                } else {
                    Text(
                        "删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { pendingDelete = row }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        AppButton(text = "添加服务器", onClick = onAdd, style = AppButtonStyle.Tonal)
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除服务器") },
            text = { Text("删除「${row.config.name}」？其登录凭据会一并清除，媒体库缓存保留。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(row.config.id)
                    pendingDelete = null
                }) { Text("删除", color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SubtitleSection(
    style: app.myplayer.core.datastore.SubtitleStyle,
    onSubtitleStyle: ((app.myplayer.core.datastore.SubtitleStyle) -> app.myplayer.core.datastore.SubtitleStyle) -> Unit,
) {
    var shownScale by remember(style.fontScale) { mutableFloatStateOf(style.fontScale) }
    var shownOutline by remember(style.outlineWidthDp) { mutableFloatStateOf(style.outlineWidthDp) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(88.dp))
            Slider(
                value = shownScale,
                onValueChange = {
                    shownScale = it
                    onSubtitleStyle { s -> s.copy(fontScale = it) }
                },
                valueRange = 0.6f..2.5f,
                modifier = Modifier.weight(1f),
            )
            Text(
                "%.1f×".format(shownScale),
                style = MaterialTheme.typography.labelMedium,
                color = LocalAppColors.current.text3,
                modifier = Modifier.width(44.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("描边", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(88.dp))
            Slider(
                value = shownOutline,
                onValueChange = {
                    shownOutline = it
                    onSubtitleStyle { s -> s.copy(outlineWidthDp = it) }
                },
                valueRange = 0f..6f,
                modifier = Modifier.weight(1f),
            )
            Text(
                "%.0fdp".format(shownOutline),
                style = MaterialTheme.typography.labelMedium,
                color = LocalAppColors.current.text3,
                modifier = Modifier.width(44.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("颜色", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(88.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    0xFFFFFFFF.toInt() to "白",
                    0xFFFDD835.toInt() to "黄",
                    0xFF4DD0E1.toInt() to "青",
                ).forEach { (argb, label) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (style.textColorArgb == argb) MaterialTheme.colorScheme.primary
                        else LocalAppColors.current.text3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (style.textColorArgb == argb) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .clickable { onSubtitleStyle { s -> s.copy(textColorArgb = argb) } }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** 单选 chip 行（即时写入，无保存按钮）；选项多时横向滚动。 */
@Composable
private fun <T> ChipRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            options.forEach { (value, text) ->
                app.myplayer.core.designsystem.components.AppChip(
                    text = text,
                    selected = selected == value,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, description: String?, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalAppColors.current.text3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
