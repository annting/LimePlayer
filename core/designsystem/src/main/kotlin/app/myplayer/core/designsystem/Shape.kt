package app.myplayer.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角体系（`docs/03-UI设计规范.md` §2.2）。
 *
 * | 用途 | 圆角 |
 * |---|---|
 * | 悬浮导航 | 999dp（**唯一**允许的胶囊） |
 * | 卡片 / 底部弹层 | 12dp |
 * | 按钮 / 输入框 / Chip / 图片 / 图标按钮 | 8dp |
 *
 * ⚠️ 除悬浮导航外，禁止出现其它胶囊形或大于 12dp 的装饰性圆角。
 * 头像这类「必须是圆」的场景用 [RoundedCornerShape.Percent] 表达，不算装饰性圆角。
 */
object AppShape {
    /** 4dp —— 徽标内的小色块、进度条端点。 */
    val tiny = RoundedCornerShape(4.dp)

    /** 8dp —— 按钮 / 输入框 / Chip / 图片 / 图标按钮。 */
    val control = RoundedCornerShape(8.dp)

    /** 12dp —— 卡片 / 列表分组 / 弹层。 */
    val card = RoundedCornerShape(12.dp)

    /** 12dp —— 底部弹层（只有上面两角）。 */
    val sheet = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)

    /** 999dp —— 悬浮底部导航，全站唯一胶囊。 */
    val nav = RoundedCornerShape(999.dp)

    /** 几何圆形（头像、锁屏点）—— 不是装饰性胶囊。 */
    val circle = RoundedCornerShape(percent = 50)
}

/**
 * 交给 `MaterialTheme.shapes`，让 M3 原生组件（Dialog / Snackbar / DropdownMenu / BottomSheet）
 * 也遵守同一套圆角。
 *
 * `large` / `extraLarge` 也压到 12dp —— 否则 `ModalBottomSheet` 会按 M3 默认的 28dp
 * 弹出「大圆角」，与「禁止胶囊/大圆角」的规范冲突（用户 2026-10-22 明确要求）。
 */
val MyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(12.dp),
)
