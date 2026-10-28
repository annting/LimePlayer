package app.myplayer.source.smb

import app.myplayer.core.model.StreamKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * MIME → 流类型 / 编码名 的纯映射。
 *
 * 这层不碰 Android API（`MediaExtractor` 的调用在 [probeMediaInfo] 里），
 * 所以能在 JVM 单测里钉死：详情页「视频/音频/字幕信息」显示成什么样，全看这张表。
 */
class MediaProbeTest {

    @Test
    fun `音视频与字幕轨能被正确分类`() {
        assertThat(kindOf("video/avc")).isEqualTo(StreamKind.VIDEO)
        assertThat(kindOf("audio/mp4a-latm")).isEqualTo(StreamKind.AUDIO)
        assertThat(kindOf("text/x-ssa")).isEqualTo(StreamKind.SUBTITLE)
        assertThat(kindOf("application/x-subrip")).isEqualTo(StreamKind.SUBTITLE)
        assertThat(kindOf("application/ttml+xml")).isEqualTo(StreamKind.SUBTITLE)
    }

    @Test
    fun `时间码等无关轨道被忽略`() {
        // MKV 里的 timecode 轨不是内容轨，塞进 UI 会平白多出「未知流」
        assertThat(kindOf("application/x-timecode")).isNull()
        assertThat(kindOf("meta/whatever")).isNull()
    }

    @Test
    fun `常见编码映射成 Emby 风格的名字`() {
        assertThat(codecNameOf("video/avc")).isEqualTo("h264")
        assertThat(codecNameOf("video/hevc")).isEqualTo("hevc")
        assertThat(codecNameOf("video/x-vnd.on2.vp9")).isEqualTo("vp9")
        assertThat(codecNameOf("audio/mp4a-latm")).isEqualTo("aac")
        assertThat(codecNameOf("audio/ac3")).isEqualTo("ac3")
        assertThat(codecNameOf("audio/eac3")).isEqualTo("eac3")
        assertThat(codecNameOf("audio/opus")).isEqualTo("opus")
        assertThat(codecNameOf("text/x-ssa")).isEqualTo("ass")
        assertThat(codecNameOf("application/x-subrip")).isEqualTo("srt")
    }

    @Test
    fun `认不出来的 mime 退化成斜杠后半段`() {
        // 宁可在 UI 上显示 "x-msvideo"，也不要显示 "UNKNOWN" 让人以为没读到
        assertThat(codecNameOf("video/x-msvideo")).isEqualTo("x-msvideo")
        assertThat(codecNameOf("audio/vnd.dts.hd")).isEqualTo("dts-hd")
    }

    @Test
    fun `语言标签只放行像语言的值`() {
        assertThat(isPlausibleLanguage("chi")).isTrue()
        assertThat(isPlausibleLanguage("eng")).isTrue()
        assertThat(isPlausibleLanguage("zh-CN")).isTrue()
        assertThat(isPlausibleLanguage("English")).isTrue()
        // 未标注
        assertThat(isPlausibleLanguage("und")).isFalse()
        assertThat(isPlausibleLanguage(null)).isFalse()
        assertThat(isPlausibleLanguage("  ")).isFalse()
        // 真机踩到：手机拍的 MP4 里语言字段是三个反引号
        assertThat(isPlausibleLanguage("```")).isFalse()
    }
}
