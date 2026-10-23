package app.myplayer.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 应用主题。
 *
 * 三件事必须在这里做完，页面里不要各自为政：
 * 1. `colorScheme`（[colorSchemeFor]）+ 扩展色 [LocalAppColors]；
 * 2. `typography`（[MyTypography]）；
 * 3. `shapes`（[MyShapes]）—— 这样 M3 原生组件（Dialog / Snackbar / BottomSheet）也自动遵守 8 / 12 圆角。
 */
@Composable
fun MyPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAppColors provides AppColors(isDark = darkTheme)) {
        MaterialTheme(
            colorScheme = colorSchemeFor(darkTheme),
            typography = MyTypography,
            shapes = MyShapes,
            content = content,
        )
    }
}
