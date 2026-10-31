package app.myplayer.core.designsystem

import androidx.compose.ui.unit.dp

/**
 * 间距阶梯（`docs/03-UI设计规范.md` §2.3：8dp 网格）。
 * 页面里不要写 `padding(14.dp)` 这类非阶梯值。
 */
object Spacing {
    /** 4dp —— 图标与文字的贴合间距。 */
    val xs = 4

    /** 8dp —— 同组元素间距。 */
    val sm = 8

    /** 12dp —— 卡片内边距下的次级间距。 */
    val md = 12

    /** 16dp —— 页面左右边距、卡片内边距。 */
    val lg = 16

    /** 24dp —— 分组之间。 */
    val xl = 24

    /** 32dp —— 页面底部留白。 */
    val xxl = 32
}

/**
 * 圆角 dp 口径（历史 API，值已按 §2.2 修正）。
 *
 * 新代码请直接用 [AppShape]（形状对象），这里只为既有 `.dp` 调用点保留。
 */
object AppRadius {
    /** 8dp —— 按钮 / 输入框 / Chip / 图片 / 图标按钮。 */
    val control = 8

    /** 8dp —— 输入框（与 [control] 同口径）。 */
    val field = 8

    /** 12dp —— 卡片 / 列表分组。 */
    val card = 12

    /** 8dp —— 海报 / 缩略图。 */
    val poster = 8

    /** 8dp —— 按钮。 */
    val button = 8

    /** 12dp —— 选源卡（本质是卡片）。 */
    val sourceCard = 12

    /** 999dp —— 仅用于悬浮导航与几何圆形。 */
    val pill = 999
}

/** 组件尺寸口径（§3）。 */
object Dimens {
    /** 页面左右边距。 */
    val pagePadding = 16.dp

    /** 分组标题与上一组之间的距离（8dp 网格上的 24dp；旧的 20dp 不在网格里）。 */
    val sectionGap = 24.dp

    /** 同组卡片之间的间距。 */
    val itemGap = 8.dp

    /** 列表行最小高度（§3.4）。 */
    val rowMinHeight = 56.dp

    /**
     * 最小触控热区（无障碍，规范 §5：所有可点元素 ≥ 48dp）。
     *
     * 视觉尺寸可以更小（Chip 32dp、图标按钮 40dp 的比例是对的），但**热区必须补到 48dp**：
     * 用「外层 48dp 可点 + 内层视觉尺寸居中」的写法，而不是把视觉尺寸本身放大。
     */
    val touchTarget = 48.dp

    /**
     * 列表行**右侧**内边距（12dp）。
     *
     * 与左侧 [pagePadding]（16dp）不对称是刻意的：chevron / 开关 / 眼睛图标都自带
     * 2–4dp 的光学留白，右侧给 12dp 才能和左侧 16dp 在视觉上等距。
     * 页面里不要再手写 12dp 字面量，统一取这个 token。
     */
    val rowEndInset = 12.dp

    /** 按钮高度（§3.2）。 */
    val button = 48.dp

    /** 输入框高度（§3.4）。 */
    val field = 48.dp

    /** Chip 高度（§3.3）。 */
    val chip = 32.dp

    /** 图标按钮直径。 */
    val iconButton = 40.dp

    /** 悬浮导航：内容高度。 */
    val navHeight = 60.dp

    /** 悬浮导航：离屏幕左右与底部的距离（§3.1 离内容 16dp）。 */
    val navMargin = 16.dp

    /**
     * 悬浮导航：导航项的高度基准。
     *
     * 选中态自 UI 优化方案 L7 起不再画「胶囊色块」（改为指示点 + 图标 + 文字），
     * 目前没有调用点；保留是因为 44dp 仍是导航项的按压热区口径，别按 60dp 铺满。
     */
    val navItem = 44.dp

    /** 极细描边。 */
    val hairline = 1.dp

    /** 页面底部为悬浮导航预留的高度（导航 + 上下留白 + 手势条）。 */
    val navReserved = 60.dp + 16.dp + 16.dp

    /**
     * 模态确认弹窗的四周内边距（`AppConfirmDialog`）。
     *
     * M3 `AlertDialog` 是 24dp 起、且三段各带一份，短文案时显得很空 —— 收到 20dp 更紧凑。
     */
    val dialogPadding = 20.dp

    /**
     * 全局隐私胶囊（右上角「隐私」小胶囊）+ 呼吸的预留宽度。
     *
     * 72dp = 胶囊约 56dp（圆点 + 文字 + 内边距）+ 右距 12dp + 4dp 呼吸，落在 8dp 网格上。
     * 播放页顶栏（锁定/更多）、媒体库详情页顶栏（排序筛选）等右上角有按钮的页面，
     * 在隐私模式下用这个值当顶栏右侧 padding，把按钮左移、给全局 overlay 胶囊让位（BUG-5）。
     */
    val privacyBadgeReserved = 72.dp
}

/**
 * 悬浮导航要占掉的底部空间，由 [MainScaffold] 下发：**胶囊顶边到屏幕底的距离 + 4dp 呼吸**
 * （= `navigationBars + navMargin + navHeight + 4dp`，一加实测约 105dp）。
 *
 * **直接整份用在滚动容器的 `contentPadding.bottom` 上，不要自己再加留白** —— 再加就会
 * 出现「滚到底空一大截」的问题。也不要用 `padding` 代替 `contentPadding`：
 * - `padding` 会裁掉 viewport，内容到那一行就"没了"，滚动时不会有东西从导航栏下穿过；
 * - `contentPadding` 只加在内容末尾，viewport 仍然铺到屏幕底，滚动中内容从半透明胶囊
 *   与底部渐变遮罩下自然地淡出，这才是真悬浮透底。
 *
 * ```
 * contentPadding = PaddingValues(bottom = LocalBottomNavSpace.current)
 * ```
 */
val LocalBottomNavSpace = androidx.compose.runtime.compositionLocalOf { 0.dp }
