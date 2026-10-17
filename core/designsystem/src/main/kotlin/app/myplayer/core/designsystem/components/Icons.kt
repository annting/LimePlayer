package app.myplayer.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 自绘图标集（线性、圆头、stroke ≈1.8dp）。
 * **不使用 material-icons 库**：material3 不传递该依赖，且图标库在新版本已冻结，
 * M1 需要的图标有限，自绘最稳、零额外依赖。（`docs/03` §2.4：24dp 线性图标、线宽 1.5–2）
 */
private fun DrawScope.iconStroke(w: Float) = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
fun BackIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val path = Path().apply {
            moveTo(w * 0.62f, h * 0.16f)
            lineTo(w * 0.30f, h * 0.5f)
            lineTo(w * 0.62f, h * 0.84f)
        }
        drawPath(path, color = tint, style = iconStroke(w))
    }
}

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
fun LockIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val body = Path().apply {
            addRoundRect(
                RoundRect(
                    left = w * 0.26f, top = h * 0.44f, right = w * 0.74f, bottom = h * 0.84f,
                    radiusX = w * 0.1f, radiusY = w * 0.1f,
                )
            )
        }
        drawPath(body, color = tint, style = iconStroke(w))
        val shackle = Path().apply {
            moveTo(w * 0.36f, h * 0.44f)
            lineTo(w * 0.36f, h * 0.36f)
            quadraticTo(w * 0.5f, h * 0.14f, w * 0.64f, h * 0.36f)
            lineTo(w * 0.64f, h * 0.44f)
        }
        drawPath(shackle, color = tint, style = iconStroke(w))
    }
}

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
fun CheckIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.55f)
            lineTo(w * 0.42f, h * 0.78f)
            lineTo(w * 0.84f, h * 0.28f)
        }
        drawPath(path, color = tint, style = iconStroke(w))
    }
}

// ---- 播放器图标 ----

/** 播放（实心三角）。 */
@Composable
fun PlayIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        val path = Path().apply {
            moveTo(w * 0.30f, h * 0.18f)
            lineTo(w * 0.84f, h * 0.5f)
            lineTo(w * 0.30f, h * 0.82f)
            close()
        }
        drawPath(path, color = tint)
    }
}

/** 暂停（两条竖条）。 */
@Composable
fun PauseIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        val bar = w * 0.16f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.26f, h * 0.2f),
            size = androidx.compose.ui.geometry.Size(bar, h * 0.6f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar * 0.35f),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.58f, h * 0.2f),
            size = androidx.compose.ui.geometry.Size(bar, h * 0.6f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar * 0.35f),
        )
    }
}

/** 快退（双左三角 + 竖挡板，播放器通用语义）。 */
@Composable
fun SeekBackIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        val bar = w * 0.10f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.16f, h * 0.24f),
            size = androidx.compose.ui.geometry.Size(bar, h * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar * 0.4f),
        )
        val tri = Path().apply {
            moveTo(w * 0.88f, h * 0.22f)
            lineTo(w * 0.88f, h * 0.78f)
            lineTo(w * 0.34f, h * 0.5f)
            close()
        }
        drawPath(tri, color = tint)
    }
}

/** 快进（双右三角 + 竖挡板）。 */
@Composable
fun SeekForwardIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        val bar = w * 0.10f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.74f, h * 0.24f),
            size = androidx.compose.ui.geometry.Size(bar, h * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar * 0.4f),
        )
        val tri = Path().apply {
            moveTo(w * 0.12f, h * 0.22f)
            lineTo(w * 0.12f, h * 0.78f)
            lineTo(w * 0.66f, h * 0.5f)
            close()
        }
        drawPath(tri, color = tint)
    }
}

/** 开锁（解锁态）。 */
@Composable
fun LockOpenIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        // 锁梁（右侧开口）
        val shackle = Path().apply {
            moveTo(w * 0.5f, h * 0.42f)
            lineTo(w * 0.5f, h * 0.3f)
            quadraticTo(w * 0.52f, h * 0.14f, w * 0.68f, h * 0.16f)
            quadraticTo(w * 0.82f, h * 0.2f, w * 0.8f, h * 0.34f)
        }
        drawPath(shackle, color = tint, style = iconStroke(w))
        // 锁体
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.2f, h * 0.42f),
            size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.44f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
            style = iconStroke(w),
        )
    }
}

/** 设置（三条滑块）。 */
@Composable
fun SettingsIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val stroke = iconStroke(w * 0.9f)
        for ((y, knobX) in listOf(0.28f to 0.66f, 0.5f to 0.34f, 0.72f to 0.56f)) {
            drawLine(tint, Offset(w * 0.16f, h * y), Offset(w * 0.84f, h * y), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawCircle(tint, radius = w * 0.1f, center = Offset(w * knobX, h * y))
        }
    }
}

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
