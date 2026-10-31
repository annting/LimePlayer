package app.myplayer.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerUtilsTest {

    @Test
    fun `formatTime 分秒`() {
        assertThat(formatTime(0)).isEqualTo("00:00")
        assertThat(formatTime(65_000)).isEqualTo("01:05")
        assertThat(formatTime(3_599_000)).isEqualTo("59:59")
    }

    @Test
    fun `formatTime 超过一小时带时`() {
        assertThat(formatTime(3_600_000)).isEqualTo("1:00:00")
        assertThat(formatTime(5_025_000)).isEqualTo("1:23:45")
    }

    @Test
    fun `formatTime 负数按零处理`() {
        assertThat(formatTime(-5)).isEqualTo("00:00")
    }

    @Test
    fun `向上滑增大（全屏高度为全量程）`() {
        // 半屏高度上滑 = +0.5
        assertThat(verticalDragValue(0.2f, deltaY = -1000f, viewHeightPx = 2000)).isWithin(0.001f).of(0.7f)
        // 向下滑减小
        assertThat(verticalDragValue(0.8f, deltaY = 1000f, viewHeightPx = 2000)).isWithin(0.001f).of(0.3f)
    }

    @Test
    fun `拖动值裁剪到 0 到 1`() {
        assertThat(verticalDragValue(0.9f, deltaY = -5000f, viewHeightPx = 2000)).isEqualTo(1f)
        assertThat(verticalDragValue(0.1f, deltaY = 5000f, viewHeightPx = 2000)).isEqualTo(0f)
    }

    @Test
    fun `高度为 0 时保持起点值`() {
        assertThat(verticalDragValue(0.42f, deltaY = -100f, viewHeightPx = 0)).isEqualTo(0.42f)
    }

    @Test
    fun `双击 seek 提示文案`() {
        assertThat(seekHintText(10_000)).isEqualTo("+10 秒")
        assertThat(seekHintText(-10_000)).isEqualTo("-10 秒")
        assertThat(seekHintText(30_000)).isEqualTo("+30 秒")
    }

    @Test
    fun `横向拖动换算进度增量：右滑前进、左滑后退`() {
        // 扫过整屏宽 = SEEK_FULL_WIDTH_MS
        assertThat(seekDeltaMs(dragPx = 1080f, widthPx = 1080f))
            .isEqualTo(SEEK_FULL_WIDTH_MS.toLong())
        // 半屏 = 一半
        assertThat(seekDeltaMs(dragPx = 540f, widthPx = 1080f))
            .isEqualTo((SEEK_FULL_WIDTH_MS / 2).toLong())
        // 左滑为负（后退）
        assertThat(seekDeltaMs(dragPx = -540f, widthPx = 1080f))
            .isEqualTo(-(SEEK_FULL_WIDTH_MS / 2).toLong())
        // 没动 = 0
        assertThat(seekDeltaMs(dragPx = 0f, widthPx = 1080f)).isEqualTo(0L)
        // 宽度异常（首帧还没量到）不能除零
        assertThat(seekDeltaMs(dragPx = 100f, widthPx = 0f)).isEqualTo(0L)
    }

    @Test
    fun `resolvePlayerOrientation forced 优先于默认值和设备方向`() {
        // BUG-2：forced 非空 = 粘性锁方向，无论设备怎么转都返回 forced
        assertThat(resolvePlayerOrientation(PlayerOrientation.AUTO, PlayerOrientation.LANDSCAPE, deviceLandscape = false))
            .isEqualTo(PlayerOrientation.LANDSCAPE)
        assertThat(resolvePlayerOrientation(PlayerOrientation.LANDSCAPE, PlayerOrientation.PORTRAIT, deviceLandscape = true))
            .isEqualTo(PlayerOrientation.PORTRAIT)
        // forced=null 时回落到默认值
        assertThat(resolvePlayerOrientation(PlayerOrientation.LANDSCAPE, null, deviceLandscape = false))
            .isEqualTo(PlayerOrientation.LANDSCAPE)
        // forced=null、默认 AUTO 时跟随设备
        assertThat(resolvePlayerOrientation(PlayerOrientation.AUTO, null, deviceLandscape = true))
            .isEqualTo(PlayerOrientation.LANDSCAPE)
        assertThat(resolvePlayerOrientation(PlayerOrientation.AUTO, null, deviceLandscape = false))
            .isEqualTo(PlayerOrientation.PORTRAIT)
    }

    @Test
    fun `releasesOnDeviceMatch 只有设备转到锁定方向才释放`() {
        assertThat(releasesOnDeviceMatch(PlayerOrientation.LANDSCAPE, deviceLandscape = true)).isTrue()
        assertThat(releasesOnDeviceMatch(PlayerOrientation.LANDSCAPE, deviceLandscape = false)).isFalse()
        assertThat(releasesOnDeviceMatch(PlayerOrientation.PORTRAIT, deviceLandscape = false)).isTrue()
        assertThat(releasesOnDeviceMatch(PlayerOrientation.PORTRAIT, deviceLandscape = true)).isFalse()
        assertThat(releasesOnDeviceMatch(null, deviceLandscape = true)).isFalse()
    }

    @Test
    fun `fakeLandscapeActive 三条件缺一不可`() {
        assertThat(fakeLandscapeActive(PlayerOrientation.LANDSCAPE, windowIsLandscape = false, inPictureInPicture = false)).isTrue()
        // 窗口已横屏（平板/折叠屏）不重复旋转（BUG-2026-007）
        assertThat(fakeLandscapeActive(PlayerOrientation.LANDSCAPE, windowIsLandscape = true, inPictureInPicture = false)).isFalse()
        // 画中画取消旋转
        assertThat(fakeLandscapeActive(PlayerOrientation.LANDSCAPE, windowIsLandscape = false, inPictureInPicture = true)).isFalse()
        // 方向机要竖屏不旋转
        assertThat(fakeLandscapeActive(PlayerOrientation.PORTRAIT, windowIsLandscape = false, inPictureInPicture = false)).isFalse()
    }

    @Test
    fun `isPortraitVideo 明显竖屏才锁`() {
        assertThat(isPortraitVideo(1080, 1920)).isTrue()   // 9:16 锁
        assertThat(isPortraitVideo(1080, 1080)).isFalse()  // 1:1 不锁
        assertThat(isPortraitVideo(1440, 1080)).isFalse()  // 4:3 不锁
        assertThat(isPortraitVideo(0, 0)).isFalse()        // 未知不锁
    }
}
