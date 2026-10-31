package app.myplayer.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体阶梯（`docs/03-UI设计规范.md` §2.3）：24 / 19 / 17 / 15.5 / 14 / 12.5 / 12 / 11.5。
 *
 * 只在这里定义字号与字重，页面里**不要**写 `fontSize = 14.sp`。
 */
val MyTypography = Typography(
    displaySmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),   // 页面主标题 / hero 标题
    headlineSmall = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),  // 顶栏标题
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),     // 区块标题
    titleMedium = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold),  // 卡片 / 弹层标题
    titleSmall = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),     // 列表行主文案
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),       // 正文
    bodySmall = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Normal),      // 次级正文
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),      // 字段 label / 按钮
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),       // 按钮文字
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),    // 辅助说明（11.5→12：中文在 360dp 屏上偏小一号）
)

/** 时间码与数字统一等宽，避免播放时数字抖动。 */
val MonoTextStyle = TextStyle(
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
    fontFamily = FontFamily.Monospace,
)

/** 小号等宽（元信息行、技术信息）。 */
val MonoLabelStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
