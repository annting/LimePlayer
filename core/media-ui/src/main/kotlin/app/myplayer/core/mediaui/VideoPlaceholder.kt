package app.myplayer.core.mediaui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.components.VideoIcon

/**
 * 卡片封面占位的**唯一外壳**：`surface2 → surface3` 渐变底 + 居中内容。
 *
 * 2026-11-02 用户要求「各种服务器类型下首页各个区域卡片用同一种显示方式」：
 * 视频 / 文件夹 / 媒体库三类卡片的「没图」状态只允许换中间的图标，底色、渐变、居中对齐
 * 必须完全一致。改配色只改这一处。
 *
 * 之前每个模块各画一套：SMB 文件夹卡自己写 `Brush.linearGradient`、视频卡走 [VideoPlaceholder]、
 * Emby 库卡走 `HomeImage`（渐变 + 文件名首字），缺图时一眼能看出三种观感。
 *
 * @param content 居中图标（用 [placeholderIconTint] 取统一图标色）
 */
@Composable
fun CardPlaceholder(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val app = LocalAppColors.current
    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(app.surface2, app.surface3))),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 占位图标的统一着色（`text3` 60%）：三类卡片图标同色，避免有的深有的浅。 */
@Composable
fun placeholderIconTint(): Color = LocalAppColors.current.text3.copy(alpha = 0.6f)

/**
 * 视频的「无预览图」占位：渐变底 + 居中视频图标。
 *
 * 2026-11-02 用户定稿：**不画文件名首字**。统一文件卡 [PosterCard] 与首页 16:9 视频卡
 * 共用这一份实现，缺图时都是「渐变底 + 视频图标」。
 *
 * @param iconSize 图标边长；默认 44dp —— 首页卡有 44dp 中央播放键与它同心，按钮会盖住中心、
 *   只露出图标外框；网格里没有播放键时图标单独显示。
 */
@Composable
fun VideoPlaceholder(
    modifier: Modifier = Modifier,
    iconSize: Dp = 44.dp,
) {
    CardPlaceholder(modifier) {
        VideoIcon(tint = placeholderIconTint(), modifier = Modifier.size(iconSize))
    }
}
