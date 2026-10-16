package app.myplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TicksTest {
    @Test fun `one second equals ten million ticks`() {
        assertThat(Ticks.ofSeconds(1).value).isEqualTo(10_000_000L)
    }
    @Test fun `ticks convert to millis by rounding down`() {
        assertThat(Ticks(10_000L).millis).isEqualTo(1L)
        assertThat(Ticks(15_999L).millis).isEqualTo(1L)
    }
    @Test fun `ticks convert to seconds`() {
        assertThat(Ticks(90_000_000L).seconds).isEqualTo(9L)
    }
    @Test fun `ofMillis round trips`() {
        assertThat(Ticks.ofMillis(1_500L)).isEqualTo(Ticks(15_000_000L))
    }
}
