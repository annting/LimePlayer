package app.myplayer.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.LocalAppColors

/**
 * 统一空态（§3）：图标块（品牌柔底 + **12dp** 圆角）+ 标题 + 一句说明 + 可选主操作。
 *
 * 首页、搜索空态、详情错误态共用，保证三处间距一致。
 *
 * @param icon 官方 Material 图标（`ImageVector`）
 * @param iconContent 自绘图标（项目里多数图标是 Canvas 画的，没有 `ImageVector`）——
 *   给了它就优先于 [icon]，两者都为 null 时退回默认播放三角
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    iconContent: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(AppShape.card)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            when {
                iconContent != null -> iconContent()
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = scheme.primary,
                )
                else -> PlayGlyph(tint = scheme.primary)
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = LocalAppColors.current.text3,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .widthIn(max = 280.dp),
            )
        }
        if (actionText != null && onAction != null) {
            Box(Modifier.padding(top = 20.dp).widthIn(min = 168.dp)) {
                AppButton(text = actionText, onClick = onAction)
            }
        }
    }
}

/** 自绘播放三角（品牌色）。 */
@Composable
private fun PlayGlyph(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.3f, h * 0.18f)
            lineTo(w * 0.3f, h * 0.82f)
            lineTo(w * 0.82f, h * 0.5f)
            close()
        }
        drawPath(path, color = tint)
    }
}
