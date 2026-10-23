package app.myplayer.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 播放页方向机（方案 A：只请求窗口方向，不转内容）。
 *
 * 这几条规则都是「纯状态 / 纯几何」，编译器和截图都发现不了，只能靠单测钉住：
 * 1. 默认方向（设置页）与临时锁定（按钮 / 竖屏视频）谁优先；
 * 2. 按钮式锁定什么时候释放（VidHub 手感）；
 * 3. 哪些画幅算「竖屏视频」——**正方形不算**（2026-10-24 用户确认口径）。
 */
class PlayerOrientationTest {

    @Test
    fun `自动模式下跟随设备`() {
        assertThat(
            resolvePlayerOrientation(PlayerOrientation.AUTO, forced = null, deviceLandscape = false)
        ).isEqualTo(PlayerOrientation.PORTRAIT)
        assertThat(
            resolvePlayerOrientation(PlayerOrientation.AUTO, forced = null, deviceLandscape = true)
        ).isEqualTo(PlayerOrientation.LANDSCAPE)
    }

    @Test
    fun `设置里的固定方向压过设备朝向`() {
        // 设置 = 竖屏：手机横着也不转
        assertThat(
            resolvePlayerOrientation(PlayerOrientation.PORTRAIT, forced = null, deviceLandscape = true)
        ).isEqualTo(PlayerOrientation.PORTRAIT)
        // 设置 = 横屏：手机竖着也横屏（VidHub 那种一进就横）
        assertThat(
            resolvePlayerOrientation(PlayerOrientation.LANDSCAPE, forced = null, deviceLandscape = false)
        ).isEqualTo(PlayerOrientation.LANDSCAPE)
    }

    @Test
    fun `按钮的临时锁定优先级最高`() {
        assertThat(
            resolvePlayerOrientation(
                PlayerOrientation.PORTRAIT,
                forced = PlayerOrientation.LANDSCAPE,
                deviceLandscape = false,
            )
        ).isEqualTo(PlayerOrientation.LANDSCAPE)
    }

    @Test
    fun `按钮式锁定在设备转到位后释放`() {
        // 点了「横屏」，用户也把手机横过来了 → 释放，跟回设备
        assertThat(releasesOnDeviceMatch(PlayerOrientation.LANDSCAPE, deviceLandscape = true)).isTrue()
        // 点了「横屏」，手机还是竖的 → 保持锁定（「不管手机什么角度都横屏」）
        assertThat(releasesOnDeviceMatch(PlayerOrientation.LANDSCAPE, deviceLandscape = false)).isFalse()
        assertThat(releasesOnDeviceMatch(PlayerOrientation.PORTRAIT, deviceLandscape = false)).isTrue()
        assertThat(releasesOnDeviceMatch(PlayerOrientation.PORTRAIT, deviceLandscape = true)).isFalse()
        // 没有锁定 / 自动模式：无从释放
        assertThat(releasesOnDeviceMatch(null, deviceLandscape = true)).isFalse()
        assertThat(releasesOnDeviceMatch(PlayerOrientation.AUTO, deviceLandscape = true)).isFalse()
    }

    @Test
    fun `正方形与 4 比 3 不算竖屏视频，只有明显比竖的才锁竖屏`() {
        // 9:16 / 3:4 → 竖屏视频，默认锁竖屏
        assertThat(isPortraitVideo(1080, 1920)).isTrue()
        assertThat(isPortraitVideo(1080, 1440)).isTrue()
        // 1:1 正方形 → **不锁**，跟随设备（横过来反而更大）
        assertThat(isPortraitVideo(1080, 1080)).isFalse()
        // 4:3 / 16:9 → 同样不锁
        assertThat(isPortraitVideo(1440, 1080)).isFalse()
        assertThat(isPortraitVideo(1920, 1080)).isFalse()
        // 尺寸未知（还没起播）：不判成竖屏
        assertThat(isPortraitVideo(0, 0)).isFalse()
    }

    @Test
    fun `假横屏只在竖窗口、非画中画、且方向机要横时生效`() {
        // 手机（窗口竖着）+ 要横 + 不在 PiP → 转内容
        assertThat(
            fakeLandscapeActive(
                PlayerOrientation.LANDSCAPE,
                windowIsLandscape = false,
                inPictureInPicture = false,
            )
        ).isTrue()
        // 平板/折叠屏：窗口本来就是横的，Android 16 会忽略 screenOrientation → 不能再转 90°
        assertThat(
            fakeLandscapeActive(
                PlayerOrientation.LANDSCAPE,
                windowIsLandscape = true,
                inPictureInPicture = false,
            )
        ).isFalse()
        // 画中画：内容必须正立，否则小窗画面侧躺
        assertThat(
            fakeLandscapeActive(
                PlayerOrientation.LANDSCAPE,
                windowIsLandscape = false,
                inPictureInPicture = true,
            )
        ).isFalse()
        // 方向机要竖：不转
        assertThat(
            fakeLandscapeActive(
                PlayerOrientation.PORTRAIT,
                windowIsLandscape = false,
                inPictureInPicture = false,
            )
        ).isFalse()
    }

    @Test
    fun `传感器角度的横屏判定是 45 度分界`() {
        assertThat(isLandscapeAngle(0)).isFalse()     // 正竖
        assertThat(isLandscapeAngle(44)).isFalse()    // 还差一点
        assertThat(isLandscapeAngle(45)).isTrue()     // 进入横屏
        assertThat(isLandscapeAngle(90)).isTrue()     // 正横
        assertThat(isLandscapeAngle(134)).isTrue()
        assertThat(isLandscapeAngle(135)).isFalse()   // 回到竖
        assertThat(isLandscapeAngle(180)).isFalse()   // 倒竖
        assertThat(isLandscapeAngle(270)).isTrue()    // 反向横屏
        assertThat(isLandscapeAngle(315)).isFalse()
    }
}
