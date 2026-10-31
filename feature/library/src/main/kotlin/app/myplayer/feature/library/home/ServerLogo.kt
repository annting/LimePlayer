package app.myplayer.feature.library.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myplayer.core.model.SourceType
import app.myplayer.feature.library.R

/**
 * 服务器类型 logo（首页左上角「切换来源」下拉 + 当前来源）。
 *
 * ## Emby / Jellyfin 用官方标志
 * 图形取自两个项目的官方品牌资源（Simple Icons 收录的 24×24 视口矢量），
 * 落在 `res/drawable/ic_server_emby.xml` / `ic_server_jellyfin.xml`：
 * - **Emby**：菱形丝带 + 中空播放三角，配品牌绿 `#52B54B`；
 * - **Jellyfin**：水母轮廓 + 播放三角挖孔，底色用它自己的紫→蓝渐变（`#AA5CC3 → #00A4DC`）。
 *
 * 只用来「标识你连的是哪一类服务器」，不做任何二次创作（Emby 官方立场也是这个口径）。
 *
 * ## 飞牛 / SMB 自绘
 * 这两类没有可当素材用的公开矢量标（飞牛影视是私有产品），继续用圆角块 + 字标：
 * 飞牛「飞」字（橙），SMB / 本地文件夹（蓝 / 灰）。四者共用同一套圆角块，视觉上是一族的。
 *
 * 尺寸默认 28dp（下拉项），顶栏用 22dp —— 圆角与字号都按 [size] 等比算，改尺寸不用改这里。
 */
@Composable
internal fun ServerLogo(
    type: SourceType,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val shape = RoundedCornerShape(size * 0.30f)
    // Jellyfin 用它自己的紫→蓝渐变，其余是纯色（统一成 Brush，后面的 background 只写一次）
    val brush: Brush = when (type) {
        SourceType.JELLYFIN -> Brush.linearGradient(listOf(JellyfinPurple, JellyfinBlue))
        SourceType.EMBY -> SolidColor(EmbyGreen)
        SourceType.FNOS -> SolidColor(FnosOrange)
        SourceType.SMB -> SolidColor(SmbBlue)
        SourceType.LOCAL -> SolidColor(LocalGray)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(brush),
        contentAlignment = Alignment.Center,
    ) {
        when (type) {
            SourceType.EMBY -> OfficialMark(R.drawable.ic_server_emby, size * 0.74f)
            SourceType.JELLYFIN -> OfficialMark(R.drawable.ic_server_jellyfin, size * 0.72f)
            SourceType.FNOS -> FnosMark(size)
            SourceType.SMB, SourceType.LOCAL -> FolderMark(size)
        }
    }
}

/** 官方矢量标：直接铺白（矢量本身就是纯色，换色只用 ColorFilter，不额外做多套资源）。 */
@Composable
private fun OfficialMark(resId: Int, box: Dp) {
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        colorFilter = ColorFilter.tint(Color.White),
        modifier = Modifier.size(box),
    )
}

/** 飞牛影视：中文首字（关掉字体缩放，免得大字号下溢出方块）。 */
@Composable
private fun FnosMark(size: Dp) {
    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f)) {
        Text(
            text = "飞",
            color = Color.White,
            fontSize = (size.value * 0.58f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** SMB / 本地：文件夹。 */
@Composable
private fun FolderMark(size: Dp) {
    Canvas(Modifier.size(size * 0.56f)) {
        val w = this.size.width
        val h = this.size.height
        drawPath(
            Path().apply {
                moveTo(w * 0.06f, h * 0.24f)
                lineTo(w * 0.44f, h * 0.24f)
                lineTo(w * 0.54f, h * 0.36f)
                lineTo(w * 0.94f, h * 0.36f)
                lineTo(w * 0.94f, h * 0.82f)
                lineTo(w * 0.06f, h * 0.82f)
                close()
            },
            Color.White,
        )
    }
}

// 品牌代表色
private val EmbyGreen = Color(0xFF52B54B)
private val JellyfinPurple = Color(0xFFAA5CC3)
private val JellyfinBlue = Color(0xFF00A4DC)
private val FnosOrange = Color(0xFFFF6B1A)
private val SmbBlue = Color(0xFF3B82F6)
private val LocalGray = Color(0xFF64748B)
