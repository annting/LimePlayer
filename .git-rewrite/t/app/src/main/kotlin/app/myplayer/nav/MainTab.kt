package app.myplayer.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.myplayer.core.designsystem.components.GridIcon
import app.myplayer.core.designsystem.components.HomeIcon
import app.myplayer.core.designsystem.components.SearchIcon
import app.myplayer.core.designsystem.components.SettingsIcon

/**
 * 手机底部导航的四个 tab（`03` §6.4，与设计板 ① 的 `.bottomnav` 顺序一致）。
 * 内容归属：媒体库 → M1 已落地；首页 / 搜索 → M3；设置 → M5。
 */
enum class MainTab(val label: String) {
    HOME("首页"),
    LIBRARY("媒体库"),
    SEARCH("搜索"),
    SETTINGS("设置");

    @Composable
    fun icon(tint: Color) {
        when (this) {
            HOME -> HomeIcon(tint)
            LIBRARY -> GridIcon(tint)
            SEARCH -> SearchIcon(tint)
            SETTINGS -> SettingsIcon(tint)
        }
    }
}
