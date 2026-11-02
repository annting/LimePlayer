package app.myplayer.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.myplayer.core.designsystem.components.GridIcon
import app.myplayer.core.designsystem.components.HomeIcon
import app.myplayer.core.designsystem.components.SearchIcon
import app.myplayer.core.designsystem.components.SettingsIcon

/**
 * 手机底部导航的四个 tab（`03` §6.4，与设计板 ① 的 `.bottomnav` 顺序一致）。
 * 内容归属：首页 → 聚合；**本机** → 手机自身视频（`docs/12-本机视频播放方案.md`）；
 * 搜索 → M3；设置 → M5。
 *
 * 2026-10-31：原「媒体库」tab 改为「本机」—— 本机是内置、常驻的本地视频入口，
 * 不是需要添加的服务器（见 `docs/12` §1）。
 */
enum class MainTab(val label: String) {
    HOME("首页"),
    LIBRARY("本机"),
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
