package app.myplayer.core.mediaui

import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.Dimens

/**
 * 媒体网格的统一度量（列数与间距）。
 *
 * 只要页面是「一片 2:3 文件卡组成的网格」，就用这一份 —— 否则同一个 App 里会出现
 * 3 列和 2 列并存（2026-11-02 实测：Emby 库 / SMB 库 / 搜索结果都是 3 列 ~105dp，
 * 只有「本机文件夹」是 2 列 ~157dp，看着像两个 App）。
 *
 * 列数口径：360dp 宽屏减去左右 [Dimens.pagePadding] 后，按 [minColumnWidth] 自适应；
 * 搜索页因为要固定 3 列（结果条数少时也要对齐），用 [fixedColumns]。
 */
object MediaGrid {
    /**
     * 单列最小宽度（92dp）。
     *
     * 360dp 屏：可用宽度 328dp，(328 + 8) / (92 + 8) ≈ 3.36 → **3 列**，单卡约 104dp。
     * 与 Plex/Netflix 的竖版海报密度一致；再小文字就不可读了。
     */
    val minColumnWidth = 92.dp

    /** 卡片间距（= [Dimens.itemGap] 8dp，横纵同值）。 */
    val spacing = Dimens.itemGap

    /** 搜索结果的固定列数。 */
    const val fixedColumns = 3
}
