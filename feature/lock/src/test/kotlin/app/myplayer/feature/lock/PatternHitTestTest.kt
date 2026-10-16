package app.myplayer.feature.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PatternHitTestTest {
    private val hit = PatternHitTest(canvasSize = 300f, hitRadius = 24f)

    @Test fun `point index follows row major order`() {
        assertThat(hit.indexAt(60f, 60f)).isEqualTo(0)   // 左上
        assertThat(hit.indexAt(150f, 150f)).isEqualTo(4)  // 中心
        assertThat(hit.indexAt(240f, 240f)).isEqualTo(8)  // 右下
    }

    @Test fun `outside hit radius returns null`() {
        assertThat(hit.indexAt(10f, 10f)).isNull()
    }

    @Test fun `point coordinates are evenly spaced`() {
        assertThat(hit.pointCenter(0)).isEqualTo(60f to 60f)
        assertThat(hit.pointCenter(8)).isEqualTo(240f to 240f)
    }
}
