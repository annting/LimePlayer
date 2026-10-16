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
