package app.myplayer.core.datastore

import kotlinx.serialization.Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class UnlockMethod { PATTERN, PIN, BIOMETRIC }
enum class LibraryDisplayMode { AUTO, TABS, DRAWER, AGGREGATE }
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
    val libraryDisplayMode: LibraryDisplayMode = LibraryDisplayMode.AUTO,
    // 服务器
    /**
     * 当前使用的服务器 id（多服务器切换）。
     * null = 未选择，回落到服务器列表第一个；新增服务器后会自动指向新服务器。
     */
    val activeServerId: String? = null,
)
