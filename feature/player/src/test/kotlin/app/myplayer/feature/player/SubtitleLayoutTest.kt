package app.myplayer.feature.player

import app.myplayer.core.playback.TrackInfo
import app.myplayer.core.playback.TrackKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 字幕排版的两条硬规则（2026-10-26 用户反馈）：
 * 1. 上半区上边线对齐、下半区下边线对齐 —— 两行字幕不许被顶出屏幕；
 * 2. 有黑边就落进黑边（竖屏看横屏片）。
 *
 * 抽成纯函数是为了不用真机就能钉死这些边界，播放页只负责把矩形量出来。
 */
class SubtitleLayoutTest {

    private val gap = 10f

    @Test
    fun `无黑边时下半区按下边线对齐`() {
        // 画面铺满舞台（1000 高），位置 0.9，文字块 40 高 → 底边落在 900，顶边 860
        val y = subtitleBlockTop(
            stageHeight = 1000f, videoTop = 0f, videoBottom = 1000f,
            blockHeight = 40, verticalPosition = 0.9f, gapPx = gap,
        )
        assertThat(y).isEqualTo(860f)
    }

    @Test
    fun `无黑边时上半区按上边线对齐`() {
        // 位置 0.1 → 顶边落在 100，两行往下长也不会溢出屏幕（旧实现会向上溢出）
        val y = subtitleBlockTop(
            stageHeight = 1000f, videoTop = 0f, videoBottom = 1000f,
            blockHeight = 40, verticalPosition = 0.1f, gapPx = gap,
        )
        assertThat(y).isEqualTo(100f)
    }

    @Test
    fun `下方有黑边时字幕落进黑边`() {
        // 竖屏看横屏片：画面 0..500，下面 500 是黑边 → 贴在画面下沿往下 10
        val y = subtitleBlockTop(
            stageHeight = 1000f, videoTop = 0f, videoBottom = 500f,
            blockHeight = 40, verticalPosition = 0.9f, gapPx = gap,
        )
        assertThat(y).isEqualTo(510f)
    }

    @Test
    fun `上方有黑边时字幕落进黑边并贴画面上沿`() {
        val y = subtitleBlockTop(
            stageHeight = 1000f, videoTop = 300f, videoBottom = 700f,
            blockHeight = 40, verticalPosition = 0.1f, gapPx = gap,
        )
        // 底边 = 画面上沿 - 10 → 顶边 250
        assertThat(y).isEqualTo(250f)
    }

    @Test
    fun `黑边放不下整块文字时退回画面内`() {
        // 下边只有 20px 黑边，文字块 40 高 → 不能塞黑边，退回画面内 0.9 处并向上长
        val y = subtitleBlockTop(
            stageHeight = 1000f, videoTop = 0f, videoBottom = 980f,
            blockHeight = 40, verticalPosition = 0.9f, gapPx = gap,
        )
        assertThat(y).isEqualTo(842f)   // 980*0.9 - 40
    }

    @Test
    fun `文字块比屏幕还高时也不会画到屏幕外`() {
        val y = subtitleBlockTop(
            stageHeight = 500f, videoTop = 0f, videoBottom = 500f,
            blockHeight = 900, verticalPosition = 0.9f, gapPx = gap,
        )
        assertThat(y).isEqualTo(0f)
    }

    // ---- 默认字幕轨选择 ----

    private fun sub(label: String, codec: String? = null) = TrackInfo(
        id = "s-$label", kind = TrackKind.SUBTITLE, label = label, codec = codec,
        isSelected = false, isSupported = true,
    )

    @Test
    fun `默认字幕优先选中文轨`() {
        val tracks = listOf(
            TrackInfo("a1", TrackKind.AUDIO, "English", "aac", true, true),
            sub("English", "srt"),
            sub("Chinese", "srt"),
        )
        assertThat(pickDefaultSubtitleTrack(tracks)?.label).isEqualTo("Chinese")
    }

    @Test
    fun `认不出语言时退回第一条字幕`() {
        val tracks = listOf(sub("Track 1"), sub("Track 2"))
        assertThat(pickDefaultSubtitleTrack(tracks)?.label).isEqualTo("Track 1")
    }

    @Test
    fun `没有字幕轨时返回 null`() {
        val tracks = listOf(TrackInfo("a1", TrackKind.AUDIO, "English", "aac", true, true))
        assertThat(pickDefaultSubtitleTrack(tracks)).isNull()
    }
}
