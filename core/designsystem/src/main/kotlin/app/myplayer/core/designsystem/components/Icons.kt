package app.myplayer.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 应用图标集。
 *
 * **播放器相关图标已改用 Google 官方 Material Icons Extended**（Apache-2.0，免费商用）——
 * 自绘 Canvas 图标在锁 / 画中画 / 横竖屏切换上精度不够（2026-10-19 用户反馈），
 * 而且 `Replay10` / `Forward10` 正好是「±10 秒」的标准图形，比自己画弧线箭头可靠得多。
 *
 * 下面仍保留少量自绘图标（人像 / 地球 / 服务器 / 首页 / 网格 / 搜索），
 * 这些是底部导航与服务器页用的，暂未替换以缩小回归面。
 */
private fun DrawScope.iconStroke(w: Float) = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
fun BackIcon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(20.dp),
)

@Composable
fun PersonIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        drawCircle(tint, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.31f), style = iconStroke(w))
        val body = Path().apply {
            moveTo(w * 0.2f, h * 0.85f)
            quadraticTo(w * 0.5f, h * 0.36f, w * 0.8f, h * 0.85f)
        }
        drawPath(body, color = tint, style = iconStroke(w))
    }
}

@Composable
fun LockIcon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.Filled.Lock,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(20.dp),
)

@Composable
fun GlobeIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val stroke = iconStroke(w)
        drawCircle(tint, radius = w * 0.36f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
        drawOval(
            tint,
            topLeft = Offset(w * 0.34f, h * 0.14f),
            size = Size(w * 0.32f, h * 0.72f),
            style = stroke,
        )
        drawLine(tint, Offset(w * 0.14f, h * 0.5f), Offset(w * 0.86f, h * 0.5f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun SearchIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val stroke = iconStroke(w)
        drawCircle(tint, radius = w * 0.26f, center = Offset(w * 0.42f, h * 0.42f), style = stroke)
        drawLine(tint, Offset(w * 0.62f, h * 0.62f), Offset(w * 0.84f, h * 0.84f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun ServerIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val stroke = iconStroke(w)
        fun slot(top: Float) {
            drawRoundRect(
                tint,
                topLeft = Offset(w * 0.16f, top),
                size = Size(w * 0.68f, h * 0.26f),
                cornerRadius = CornerRadius(w * 0.08f),
                style = stroke,
            )
            drawCircle(tint, radius = w * 0.045f, center = Offset(w * 0.27f, top + h * 0.13f))
        }
        slot(h * 0.16f)
        slot(h * 0.56f)
    }
}

@Composable
fun CheckIcon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.Filled.Check,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(18.dp),
)

// ---- 播放器图标 ----

/** 播放（实心三角）。 */
@Composable
fun PlayIcon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.Filled.PlayArrow,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(24.dp),
)

/** 暂停（两条竖条）。 */
@Composable
fun PauseIcon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.Filled.Pause,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(24.dp),
)

/** 快退（双左三角 + 竖挡板，播放器通用语义）。 */
@Composable
fun SeekBackIcon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.Filled.Replay10,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(24.dp),
)

/** 快进（双右三角 + 竖挡板）。 */
@Composable
fun SeekForwardIcon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.Filled.Forward10,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(24.dp),
)

/** 开锁（解锁态）。 */
@Composable
fun LockOpenIcon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.Filled.LockOpen,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(20.dp),
)

/** 设置（三条滑块）。 */
@Composable
fun SettingsIcon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.Filled.Settings,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(20.dp),
)

/** 首页（房子）。 */
@Composable
fun HomeIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        val roof = Path().apply {
            moveTo(w * 0.12f, h * 0.46f)
            lineTo(w * 0.5f, h * 0.14f)
            lineTo(w * 0.88f, h * 0.46f)
        }
        drawPath(roof, color = tint, style = iconStroke(w))
        val body = Path().apply {
            moveTo(w * 0.22f, h * 0.42f)
            lineTo(w * 0.22f, h * 0.84f)
            lineTo(w * 0.78f, h * 0.84f)
            lineTo(w * 0.78f, h * 0.42f)
        }
        drawPath(body, color = tint, style = iconStroke(w))
        drawLine(tint, Offset(w * 0.44f, h * 0.84f), Offset(w * 0.44f, h * 0.64f), strokeWidth = iconStroke(w).width, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.44f, h * 0.64f), Offset(w * 0.58f, h * 0.64f), strokeWidth = iconStroke(w).width, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.58f, h * 0.64f), Offset(w * 0.58f, h * 0.84f), strokeWidth = iconStroke(w).width, cap = StrokeCap.Round)
    }
}

/** 媒体库（四宫格）。 */
@Composable
fun GridIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        val s = iconStroke(w * 0.9f)
        for (r in 0..1) {
            for (c in 0..1) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * (0.16f + 0.36f * c), h * (0.16f + 0.36f * r)),
                    size = androidx.compose.ui.geometry.Size(w * 0.28f, h * 0.28f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
                    style = s,
                )
            }
        }
    }
}

/** 画中画（外框 + 右下角小窗，与设置抽屉并列在控制层）。 */
@Composable
fun PictureInPictureIcon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.Filled.PictureInPictureAlt,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(20.dp),
)

/** 横竖屏切换：竖放 / 横放两个机身外框 + 右下角转向箭头。 */
@Composable
fun ScreenRotateIcon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.Filled.ScreenRotation,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(20.dp),
)

/** 右上角「更多」：竖排三个点（打开完整设置面板）。 */
@Composable
fun MoreVerticalIcon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.Filled.MoreVert,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(20.dp),
)

/** 快退 10 秒（Material `Replay10`：圆环箭头 + 内置「10」，是标准图形）。 */
@Composable
fun SeekBack10Icon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.Filled.Replay10,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(24.dp),
)

/** 快进 10 秒（Material `Forward10`）。 */
@Composable
fun SeekForward10Icon(tint: Color, modifier: Modifier = Modifier) = Icon(
    imageVector = Icons.Filled.Forward10,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(24.dp),
)
