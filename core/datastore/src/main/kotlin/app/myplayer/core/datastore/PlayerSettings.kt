package app.myplayer.core.datastore

import kotlinx.serialization.Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class UnlockMethod { PATTERN, PIN, BIOMETRIC }
enum class LibraryDisplayMode { AUTO, TABS, DRAWER, AGGREGATE }
enum class ScreenOrientation { AUTO, LANDSCAPE, PORTRAIT }
enum class AspectRatio { FIT, FILL, CROP, ORIGINAL }

@Serializable
data class SubtitleStyle(
    val fontScale: Float = 1.0f,
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val outlineColorArgb: Int = 0xFF000000.toInt(),
    val outlineWidthDp: Float = 2f,
    val bottomPaddingDp: Float = 16f,
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
    val allowCleartextHttp: Boolean = false,
    val trustSelfSignedCert: Boolean = false,
    // 预览
    val localThumbnails: Boolean = false,
    // 搜索
    val searchFuzzyPinyin: Boolean = false,
    val searchTraditionalSimplified: Boolean = false,
    // 界面
    val libraryDisplayMode: LibraryDisplayMode = LibraryDisplayMode.AUTO,
)
