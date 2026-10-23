package app.myplayer.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors

/**
 * 扁平表单输入框（`docs/03-UI设计规范.md` §3.4）。
 *
 * 规格：label（12sp，`text3`）+ 48dp 触控高度，**无底色 / 无描边 / 无指示线**；
 * 值一律右对齐（`TextAlign.End`），占位符同样右对齐；密码的显示/隐藏眼睛贴在最右侧。
 *
 * 为什么没有 `containerColor`：这里用的是 [BasicTextField]（不是 M3 的 `TextField`），
 * 根本没有 indicator 与容器色，扁平是默认行为 —— 也就不存在「设成透明」这一步。
 * 焦点提示只靠青柠色光标，不再画 box（box 就是要去掉的指示线）。
 */
@Composable
fun AppTextField(
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
    val app = LocalAppColors.current
    var passwordVisible by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = app.text3,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.field),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (leadingIcon) {
                FieldIcon.Person -> PersonIcon(tint = app.text3)
                FieldIcon.Lock -> LockIcon(tint = app.text3)
                FieldIcon.Globe -> GlobeIcon(tint = app.text3)
                FieldIcon.Server -> ServerIcon(tint = app.text3)
                FieldIcon.None -> {}
            }
            if (leadingIcon != FieldIcon.None) {
                Box(Modifier.size(8.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) scheme.onSurface else app.text3,
                    textAlign = TextAlign.End,
                ),
                cursorBrush = SolidColor(scheme.primary),
                visualTransformation = if (isPassword && !passwordVisible) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = app.text3,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (isPassword) {
                // 眼睛紧贴右缘：只占 16dp，与列表行里的下拉箭头同位
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(40.dp)
                        .clickable { passwordVisible = !passwordVisible },
                    contentAlignment = Alignment.Center,
                ) {
                    EyeIcon(
                        visible = passwordVisible,
                        tint = app.text3,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** 旧名称，保留以兼容既有调用点；新代码请用 [AppTextField]。 */
@Deprecated("改用 AppTextField", ReplaceWith("AppTextField(...)"))
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
) = AppTextField(
    label = label,
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    placeholder = placeholder,
    leadingIcon = leadingIcon,
    isPassword = isPassword,
    keyboardType = keyboardType,
    imeAction = imeAction,
    enabled = enabled,
)

/** 自绘眼睛图标（显示 / 隐藏密码）；不引入扩展图标库。设置列表行内嵌输入框也复用它。 */
@Composable
internal fun EyeIcon(
    visible: Boolean,
    tint: Color,
    modifier: Modifier = Modifier.size(18.dp),
) {
    Canvas(modifier) {
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
