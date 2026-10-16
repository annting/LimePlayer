package app.myplayer.core.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import app.myplayer.core.model.PlaybackPlan
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `Media3PlaybackEngine` 的基础契约（Robolectric 上跑，不需要真机）：
 * 生命周期状态、资源释放、以及 `PlaybackPlan` → `MediaItem` 的映射。
 * 真实播放行为在真机上走查（见 `04` §2 的 M2 验收路径）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Media3PlaybackEngineTest {

    private fun engine(): Media3PlaybackEngine {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Media3PlaybackEngine(context)
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `初始状态为空闲且未播放`() {
        val e = engine()
        val s = e.state.value
        assertThat(s.status).isEqualTo(EngineStatus.IDLE)
        assertThat(s.isPlaying).isFalse()
        assertThat(s.positionMs).isEqualTo(0L)
        assertThat(s.durationMs).isEqualTo(0L)
        assertThat(s.error).isNull()
        e.release()
    }

    @Test
    fun `prepare 后脱离空闲态`() {
        val e = engine()
        e.prepare(
            PlaybackPlan.Direct(uri = "http://127.0.0.1:1/stream.mp4", mediaSourceId = "ms1"),
            startPositionMs = 0L,
        )
        idleMain()
        // 无真实网络：进入 BUFFERING（尝试加载）或已 FAILED；关键断言是「已脱离 IDLE」
        assertThat(e.state.value.status).isNotEqualTo(EngineStatus.IDLE)
        e.release()
    }

    @Test
    fun `release 幂等且释放后状态不变为播放中`() {
        val e = engine()
        e.prepare(PlaybackPlan.Direct(uri = "http://127.0.0.1:1/a.mp4"), startPositionMs = 0L)
        e.play()
        e.release()
        e.release()   // 二次 release 不得抛异常
        idleMain()
        assertThat(e.state.value.isPlaying).isFalse()
    }

    @Test
    fun `transcode 计划显式标注 HLS MIME`() {
        val item = PlaybackPlan.Transcode(
            hlsUrl = "http://192.168.31.15:8096/videos/1/master.m3u8?api_key=T&PlaySessionId=s",
            isRemuxOnly = false,
        ).toMediaItem()
        assertThat(item.localConfiguration?.mimeType).isEqualTo(MimeTypes.APPLICATION_M3U8)
    }

    @Test
    fun `direct 计划不强行标注 MIME（交给推断）`() {
        val item = PlaybackPlan.Direct(
            uri = "http://192.168.31.15:8096/Videos/1/stream?static=true&api_key=T",
            mediaSourceId = "ms1",
        ).toMediaItem()
        assertThat(item.localConfiguration?.mimeType).isNull()
    }
}
