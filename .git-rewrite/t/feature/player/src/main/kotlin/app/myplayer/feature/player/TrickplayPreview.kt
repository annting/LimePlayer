package app.myplayer.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.TrickplaySpec
import coil3.compose.rememberAsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import kotlinx.coroutines.delay

/**
 * 进度条拖动预览（`03` §5.4 / 任务 3-2）。
 *
 * 从 trickplay 分片图里裁出「当前拖动位置」对应的格子：
 * 分片是 `列数 x 行数` 的拼图，第 index 格位于 (index % cols, index / cols)。
 * 无 trickplay（服务端未预生成）时不渲染 —— 由调用方判断。
 */
@Composable
fun TrickplayPreview(
    vm: PlayerViewModel,
    spec: TrickplaySpec,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val index = spec.tileIndexAt(positionMs)
    val (col, row) = spec.cellOf(index)

    // 拖动时节流请求（80ms）：避免快速划过一格一请求
    var source by remember { mutableStateOf<ImageSource?>(null) }
    LaunchedEffect(index) {
        delay(80)
        source = vm.previewTile(positionMs)
    }

    val painter = when (val s = source) {
        is ImageSource.Remote -> rememberAsyncImagePainter(
            ImageRequest.Builder(context)
                .data(s.url)
                .httpHeaders(
                    NetworkHeaders.Builder().apply { s.headers.forEach { (k, v) -> set(k, v) } }.build()
                )
                .build()
        )
        is ImageSource.Local -> rememberAsyncImagePainter(
            ImageRequest.Builder(context).data(s.uri).build()
        )
        else -> null
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(168.dp, 95.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.85f)),
        ) {
            if (painter != null) {
                Canvas(Modifier.size(168.dp, 95.dp)) {
                    // 预览尺寸 = 单格尺寸；整图放大到 cols*rows 后平移到目标格
                    val cellW = size.width
                    val cellH = size.height
                    clipRect {
                        translate(left = -col * cellW, top = -row * cellH) {
                            with(painter) {
                                draw(size = Size(cellW * spec.columns, cellH * spec.rows))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            formatTime(positionMs),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}
