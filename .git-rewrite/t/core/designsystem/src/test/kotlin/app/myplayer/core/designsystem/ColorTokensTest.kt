package app.myplayer.core.designsystem

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 取值以 `docs/03-UI设计规范.md` §2.2 为准（黄绿系 lime，与 app 图标一致）。 */
class ColorTokensTest {
    @Test fun `dark scheme uses dark background`() {
        val scheme = colorSchemeFor(isDark = true)
        assertThat(scheme.background).isEqualTo(Color(0xFF0B0D11))
    }

    @Test fun `light scheme uses light background`() {
        assertThat(colorSchemeFor(isDark = false).background).isEqualTo(Color(0xFFF4F6F9))
    }

    @Test fun `brand primary differs between themes`() {
        assertThat(colorSchemeFor(false).primary).isEqualTo(Color(0xFF7CB342))
        assertThat(colorSchemeFor(true).primary).isEqualTo(Color(0xFFAED581))
    }

    @Test fun `error follows theme danger palette`() {
        assertThat(colorSchemeFor(false).error).isEqualTo(DangerLight)
        assertThat(colorSchemeFor(true).error).isEqualTo(DangerDark)
    }
}
