package app.myplayer.core.datastore

import kotlinx.serialization.Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class UnlockMethod { PATTERN, PIN, BIOMETRIC }
enum class ScreenOrientation { AUTO, LANDSCAPE, PORTRAIT }
enum class AspectRatio { FIT, FILL, CROP, ORIGINAL }

@Serializable
data class SubtitleStyle(
    /** 字号，单位 sp（字幕设置页范围 12–32）。 */
    val fontSizeSp: Float = 20f,
    /** 字幕填充色（ARGB）。 */
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    /** 描边色（ARGB）。 */
    val outlineColorArgb: Int = 0xFF000000.toInt(),
    /** 描边粗细，单位 dp（0 = 不描边）。 */
    val outlineWidthDp: Float = 2f,
    /** 字幕在画面中的垂直位置：0.1 = 靠上，0.9 = 靠下。 */
    val verticalPosition: Float = 0.9f,
    /**
     * 起播时自动开启字幕（2026-10-26 用户需求）。
     *
     * 之前没有任何自动选择逻辑：服务端给了外挂/内嵌字幕轨也不会被选中，
     * 每次都得在播放页手动点一次「字幕 → 中文」。
     */
    val defaultEnabled: Boolean = true,
)

/**
 * 首页各模块的显示规则（首页设置页）。
 *
 * Emby 与 SMB **各存一份**（`PlayerSettings.embyHomeSettings` / `smbHomeSettings`）：
 * 两种源的模块语义不同（库入口 vs 扫描文件夹），用户经常要一边全开、另一边精简。
 *
 * [recentlyAddedLimit] / [recentlyAddedDays] 只在 `showRecentlyAdded = true` 时生效，
 * 但值始终保留 —— 关掉再打开不会丢用户上次选的档位。
 */
@Serializable
data class HomeModuleSettings(
    /** 模块一：继续观看。 */
    val showContinueWatching: Boolean = true,
    /** 模块二：库入口（Emby = 我的媒体库 / SMB = 我的文件夹）。 */
    val showLibraryEntries: Boolean = true,
    /** 模块三：每个库/文件夹一行的动态内容。 */
    val showDynamicSections: Boolean = true,
    /** 模块四：最近添加（关闭时第三组的数量 / 天数置灰）。 */
    val showRecentlyAdded: Boolean = true,
    /** 最近添加显示数量（条）。 */
    val recentlyAddedLimit: Int = 20,
    /** 最近添加时间范围（天）。 */
    val recentlyAddedDays: Int = 7,
)

/** 与功能说明的设置项清单一一对应；分组顺序和设置页一致，便于 P5 直接渲染。 */
@Serializable
data class PlayerSettings(
    // 外观
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // 播放
    val defaultSpeed: Float = 1.0f,
    val keepBackgroundPlayback: Boolean = false,
    val doubleTapSeekMillis: Long = 10_000L,
    val autoPlayNext: Boolean = true,
    // 画面
    val screenOrientation: ScreenOrientation = ScreenOrientation.AUTO,
    val defaultAspectRatio: AspectRatio = AspectRatio.FIT,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    // 隐私
    val appLockEnabled: Boolean = true,
    val unlockMethod: UnlockMethod = UnlockMethod.PATTERN,
    val unlockGraceMillis: Long = 0L,
    val hideRecentsSnapshot: Boolean = false,
    // 网络
    // 与 `01` §6 的「默认关」偏离：家庭 NAS 几乎全是 http://，默认关 = 开箱连不上；
    // 安全收紧由用户在设置页主动关闭（OkHttp 拦截器即刻生效）
    val allowCleartextHttp: Boolean = true,
    val trustSelfSignedCert: Boolean = false,
    // 预览
    val localThumbnails: Boolean = true,
    // 搜索
    val searchFuzzyPinyin: Boolean = false,
    val searchTraditionalSimplified: Boolean = false,
    // 界面
    // 注：「库展示方式」（自动 / 页签 / 抽屉 / 聚合）**暂不提供** —— 库页目前只有一种布局，
    // 开关改了没有任何效果，属于「假开关」，2026-10-25 从设置页摘除、记入待开发（docs/04）。
    // 字段与枚举一并删除；旧的 JSON 里残留 key 由 ignoreUnknownKeys 忽略，不需要迁移。
    /**
     * 首页模块显示规则，Emby 与 SMB **各一份**（首页设置页）。
     * SMB 源尚未实现扫描链路，先落配置 —— 首页 UI（`HomeSourceType`）已经在消费这两个值。
     */
    val embyHomeSettings: HomeModuleSettings = HomeModuleSettings(),
    val smbHomeSettings: HomeModuleSettings = HomeModuleSettings(),
    // 服务器
    /**
     * 当前使用的服务器 id（多服务器切换）。
     * null = 未选择，回落到服务器列表第一个；新增服务器后会自动指向新服务器。
     */
    val activeServerId: String? = null,
)
