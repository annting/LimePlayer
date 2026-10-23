package app.myplayer.core.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
 * - 选中项：背景变 `primary`，图标与文字变 `brandInk`，图标有一次 `1 → 1.16 → 1` 弹跳；
 * - 未选中项：只有图标，`onSurfaceVariant`。
 *
 * 每个 tab 占等宽 1/4，选中胶囊在格内居中，**切换时其它项不会被挤着跑**。
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
    val app = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }

    // 选中底色：primary（§3.1「选中项背景变为 primary」）
    val background by animateColorAsState(
        targetValue = if (selected) scheme.primary else Color.Transparent,
        animationSpec = tween(durationMillis = 260),
        label = "nav-bg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) app.brandInk else scheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 260),
        label = "nav-fg",
    )
    val iconScale = bounceScale(active = selected)

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
        Row(
            modifier = Modifier
                .pressScale(interaction, pressedScale = 0.95f)
                .height(Dimens.navItem)
                .clip(AppShape.nav)
                .background(background)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.scale(iconScale)) { spec.icon(fg) }
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(tween(160)) + expandHorizontally(tween(220)),
                exit = fadeOut(tween(100)) + shrinkHorizontally(tween(180)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = spec.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = fg,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
