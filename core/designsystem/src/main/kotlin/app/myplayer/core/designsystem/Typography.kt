package app.myplayer.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体层级（`docs/03-UI设计规范.md` §2.3）。
 * 等宽一律 [FontFamily.Monospace]（时间码 / 数字，避免跳动）。
 */
val MyTypography = Typography(
    displaySmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),      // 页面主标题
    headlineSmall = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),     // AppBar 标题
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),        // 区块标题
    titleMedium = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold),     // 卡片 / 弹层标题
    titleSmall = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),        // 海报卡标题
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),          // 正文
    bodySmall = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Normal),         // 次级正文
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),         // 字段 label
    labelSmall = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Normal),        // 辅助说明
)

/** 时间码与数字统一等宽。 */
val MonoTextStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
val MonoLabelStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
