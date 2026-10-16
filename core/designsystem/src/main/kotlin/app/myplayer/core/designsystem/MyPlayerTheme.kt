package app.myplayer.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 应用主题。`darkTheme` 由调用方决定（M1 传系统值；P5 接设置项 `themeMode` 后再改为三态）。
 */
@Composable
fun MyPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = colorSchemeFor(darkTheme), content = content)
}
