package app.myplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.components.AppChip
import app.myplayer.core.designsystem.components.CheckIcon
import app.myplayer.core.playback.TrackInfo
import app.myplayer.core.playback.TrackKind

/**
 * 播放器设置弹层（`03` §5.6 / 任务 2-6）：画面比例 / 倍速 / 音轨 / 字幕，
 * 全部收进一个 [ModalBottomSheet]，不并排平铺按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(
    state: PlayerUiState,
    /** 播放器是否处于「窗口内旋转」的横屏版式；此时不能用 ModalBottomSheet（独立窗口不跟着转）。 */
    rotated: Boolean,
    onDismiss: () -> Unit,
    onScaleMode: (ScaleMode) -> Unit,
    onSpeed: (Float) -> Unit,
    onTrack: (TrackKind, String?) -> Unit,
) {
    if (rotated) {
        // 旋转态：同一坐标系里的浮层，跟着播放器一起转
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDismiss() }
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp, bottom = 16.dp),
            ) {
                SheetContent(state, onScaleMode, onSpeed, onTrack)
            }
        }
        return
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surface,
        contentColor = scheme.onSurface,
        dragHandle = {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(app.text3.copy(alpha = 0.4f)),
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            SheetContent(state, onScaleMode, onSpeed, onTrack)
        }
    }
}

/** 设置面板的内容（ModalBottomSheet 与旋转态浮层共用）。 */
@Composable
private fun SheetContent(
    state: PlayerUiState,
    onScaleMode: (ScaleMode) -> Unit,
    onSpeed: (Float) -> Unit,
    onTrack: (TrackKind, String?) -> Unit,
) {
    SectionTitle("画面比例")
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        ScaleMode.entries.forEachIndexed { i, mode ->
            if (i > 0) Spacer(Modifier.width(8.dp))
            AppChip(
        text = mode.label,
        selected = state.scaleMode == mode,
        onClick = { onScaleMode(mode) },
            )
        }
    }

    SectionTitle("播放速度")
    Speeds.chunked(4).forEachIndexed { rowIndex, row ->
        if (rowIndex > 0) Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            row.forEachIndexed { i, (speed, label) ->
        if (i > 0) Spacer(Modifier.width(8.dp))
        AppChip(
            text = label,
            selected = state.speed == speed,
            onClick = { onSpeed(speed) },
        )
            }
        }
    }

    SectionTitle("音轨")
    TrackSection(
        tracks = state.tracks.filter { it.kind == TrackKind.AUDIO },
        emptyText = "该视频只有一条音频（或暂未解析出轨道）",
        onSelect = { onTrack(TrackKind.AUDIO, it) },
        allowOff = false,
    )

    SectionTitle("字幕")
    TrackSection(
        tracks = state.tracks.filter { it.kind == TrackKind.SUBTITLE },
        emptyText = "该视频没有内嵌字幕",
        onSelect = { onTrack(TrackKind.SUBTITLE, it) },
        allowOff = true,
    )
}

/** 倍速档位（`03` §5.6：0.5X–3.0X，步进 0.25× 的子集，避免抽屉过长）。底部快捷浮层共用同一份。 */
internal val Speeds: List<Pair<Float, String>> = listOf(
    0.5f to "0.5x",
    0.75f to "0.75x",
    1f to "1.0x",
    1.25f to "1.25x",
    1.5f to "1.5x",
    1.75f to "1.75x",
    2f to "2.0x",
    2.5f to "2.5x",
    3f to "3.0x",
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalAppColors.current.text3,
        modifier = Modifier.padding(top = 18.dp, bottom = 10.dp),
    )
}

@Composable
private fun TrackSection(
    tracks: List<TrackInfo>,
    emptyText: String,
    onSelect: (String?) -> Unit,
    allowOff: Boolean,
) {
    val app = LocalAppColors.current
    if (tracks.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodySmall, color = app.text3)
        return
    }
    if (allowOff) {
        TrackRow(
            label = "关闭字幕",
            sub = null,
            selected = tracks.none { it.isSelected },
            enabled = true,
            onClick = { onSelect(null) },
        )
    }
    tracks.forEach { t ->
        TrackRow(
            label = t.label,
            sub = t.codec?.let { c -> if (t.isSupported) c else "$c · 当前设备不支持" },
            selected = t.isSelected,
            enabled = t.isSupported,
            onClick = if (t.isSupported) ({ onSelect(t.id) }) else null,
        )
    }
}

@Composable
private fun TrackRow(
    label: String,
    sub: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) scheme.onSurface else app.text3,
            )
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = app.text3)
            }
        }
        if (selected) CheckIcon(tint = scheme.primary)
    }
}
