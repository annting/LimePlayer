package app.myplayer.feature.player

/**
 * 字幕外观（播放页渲染用）—— 与 `core:datastore` 的 `SubtitleStyle` **字段一一对应**。
 *
 * 为什么不直接用它：播放模块不该知道 DataStore 的存在（同 [PlayerOrientation] 的处理），
 * 映射统一放在 `MainActivity` —— 那里是唯一同时看得见「设置」与「播放」两个模块的地方。
 *
 * 默认值与旧行为保持一致（16sp / 白字 / 无描边 / 贴画面下方 10%），
 * 这样没读过设置时（首帧、预览）画面不会跳变。
 */
data class PlayerSubtitleStyle(
    /** 字号，sp。 */
    val fontSizeSp: Float = 16f,
    /** 字幕填充色（ARGB）。 */
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    /** 描边色（ARGB）。 */
    val outlineColorArgb: Int = 0xFF000000.toInt(),
    /** 描边粗细，dp；`<= 0` = 不描边。 */
    val outlineWidthDp: Float = 0f,
    /** 字幕垂直位置：0.1 = 靠上，0.9 = 靠下。 */
    val verticalPosition: Float = 0.9f,
)
