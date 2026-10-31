package app.myplayer.core.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors

/**
 * 胶囊背景的不透明度：留 5% 让底下滑过的海报/文字透出来一点点（"悬浮"的关键手感）。
 * 再低就开始影响图标文字的可读性，实测 0.95 是两种主题下的平衡点。
 */
private const val NAV_SURFACE_ALPHA = 0.95f

/** 悬浮导航的一项。图标用 `(tint) -> Unit` 传入，方便复用自绘图标集。 */
@Immutable
data class NavItemSpec(
    val label: String,
    val icon: @Composable (tint: Color) -> Unit,
)

/**
 * 悬浮式胶囊底部导航（`docs/03-UI设计规范.md` §3.1）。
 *
 * 规格：
 * - 悬浮在内容上方 `16dp`，左右各 `16dp`，底部再让出手势条（[navigationBarsPadding]）；
 * - 形状 **999dp 胶囊**（全站唯一允许的胶囊）；底色 `surface` + 12dp 阴影 + 1dp 细描边；
 * - 选中项：**图标与文字变 `primary` + 图标上方一枚 5dp 指示点**；未选中只降为 `onSurfaceVariant`；
 * - 每个 tab 占等宽 1/4，**四项结构完全一致**（都是「指示点 + 图标 + 文字」三行）。
 *
 * ## 为什么不再用「背景色块 + 展开文字」（UI 优化方案 L7）
 * 老做法是选中项撑出一个 primary 色块、未选中项只有图标：
 * - 颜色块一到手就「长大」，文字展开又把图标从格子中心推开 → 切换时整排都在动；
 * - 选中项图标还叠了 `bounceScale`（1 → 1.16 → 1），两个状态的图标尺寸不一致。
 * 现在四项同构、指示点用**透明占位**（不改变布局高度），切换只变色，
 * 图标位置与尺寸在两种状态下逐像素相同。
 */
@Composable
fun FloatingBottomNav(
    items: List<NavItemSpec>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Dimens.navMargin, vertical = Dimens.navMargin),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.navHeight)
                // 阴影要在 clip 之前、且 clip = false：先画出胶囊外形的投影，再按胶囊裁剪内容
                .shadow(elevation = 12.dp, shape = AppShape.nav, clip = false)
                .clip(AppShape.nav)
                // 关键：**半透明** surface。容器 Box 完全透明，这一层若用不透明色，
                // 就等于在内容上盖了一块实心板，滚动到下面时什么都看不见（"悬浮"变"贴底"）
                .background(scheme.surface.copy(alpha = NAV_SURFACE_ALPHA))
                .border(Dimens.hairline, app.border, AppShape.nav)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                NavCell(
                    spec = item,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.NavCell(
    spec: NavItemSpec,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }

    val fg by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 220),
        label = "nav-fg",
    )
    // 指示点只做透明度：位置与尺寸恒定，切换时不会把下面的图标顶下去
    val dotAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "nav-dot",
    )

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .pressScale(interaction, pressedScale = 0.96f)
                .padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(NavDotSize)
                    .clip(AppShape.circle)
                    .background(scheme.primary.copy(alpha = dotAlpha)),
            )
            Spacer(Modifier.height(3.dp))
            Box(Modifier.size(NavIconSize)) { spec.icon(fg) }
            Spacer(Modifier.height(2.dp))
            Text(
                text = spec.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = fg,
                maxLines = 1,
            )
        }
    }
}

/** 选中指示点直径（§3.1「品牌色图标 + 顶部指示点」）。 */
private val NavDotSize = 5.dp

/** 图标槽位：四项图标本体都是 20dp，用固定槽位兜住，避免个别图标尺寸不同导致基线错位。 */
private val NavIconSize = 20.dp
