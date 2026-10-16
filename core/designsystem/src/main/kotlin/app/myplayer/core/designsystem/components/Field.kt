package app.myplayer.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myplayer.core.designsystem.AppRadius
import app.myplayer.core.designsystem.LocalAppColors

/**
 * 表单输入框（设计板 `.field`）：label（12sp）+ 48dp 输入框（14dp 圆角、surface 底、描边；
 * 聚焦时描边变 brand）。密码类型自带「显示 / 隐藏」切换。
 */
@Composable
fun AppField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: FieldIcon = FieldIcon.None,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(AppRadius.field.dp)
    var passwordVisible by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalAppColors.current.text3,
            modifier = Modifier.padding(bottom = 7.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(shape)
                .background(if (enabled) scheme.surface else scheme.surfaceVariant)
                .border(1.dp, if (focused) scheme.primary else scheme.outline, shape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconTint = LocalAppColors.current.text3
            when (leadingIcon) {
                FieldIcon.Person -> PersonIcon(tint = iconTint)
                FieldIcon.Lock -> LockIcon(tint = iconTint)
                FieldIcon.Globe -> GlobeIcon(tint = iconTint)
                FieldIcon.Server -> ServerIcon(tint = iconTint)
                FieldIcon.None -> {}
            }
            if (leadingIcon != FieldIcon.None) {
                Box(Modifier.size(9.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    interactionSource = interaction,
                    visualTransformation = if (isPassword && !passwordVisible) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) {
                                Text(
                                    placeholder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LocalAppColors.current.text3,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            if (isPassword) {
                Box(Modifier.padding(start = 8.dp)) {
                    CircleIconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        modifier = Modifier.size(32.dp),
                    ) {
                        EyeIcon(visible = passwordVisible, tint = LocalAppColors.current.text3)
                    }
                }
            }
        }
    }
}

/** 自绘眼睛图标（显示 / 隐藏密码）；不用扩展图标库，避免额外依赖。 */
@Composable
private fun EyeIcon(visible: Boolean, tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.6.dp.toPx()
        val outline = Path().apply {
            moveTo(w * 0.08f, h * 0.5f)
            quadraticTo(w * 0.5f, h * 0.02f, w * 0.92f, h * 0.5f)
            quadraticTo(w * 0.5f, h * 0.98f, w * 0.08f, h * 0.5f)
            close()
        }
        drawPath(outline, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round))
        drawCircle(tint, radius = w * 0.135f, center = Offset(w / 2f, h / 2f))
        if (!visible) {
            drawLine(tint, Offset(w * 0.16f, h * 0.84f), Offset(w * 0.84f, h * 0.16f), sw, StrokeCap.Round)
        }
    }
}

/** 输入框左侧图标选择（自绘图标集，见 Icons.kt）。 */
enum class FieldIcon { None, Person, Lock, Globe, Server }
