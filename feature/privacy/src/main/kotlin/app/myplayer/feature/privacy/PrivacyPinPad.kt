package app.myplayer.feature.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.components.pressScale

/** 隐私 PIN 的固定位数。与 `PinPrivacyKek.MIN_LENGTH` 一致，UI 层不暴露 6–12 的自由度。 */
const val PRIVACY_PIN_LENGTH: Int = 6

/**
 * 已输入位数的圆点指示器。
 *
 * 不用 `TextField`：密码输入框会拉起输入法，而输入法（尤其第三方）是隐私模式下
 * 明确要回避的一面 —— 方案 §P0#3 列了输入法/剪贴板/Autofill 的泄露面。下面自绘的
 * 键盘让 PIN 一个字符都不进系统输入法。
 */
@Composable
fun PinDots(
    filled: Int,
    modifier: Modifier = Modifier,
    length: Int = PRIVACY_PIN_LENGTH,
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    Row(
        modifier = modifier.semantics {
            contentDescription = "已输入 $filled 位，共 $length 位"
        },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(length) { index ->
            val on = index < filled
            // 槽位恒为 14dp，只有**画出来的圆**在 10dp / 14dp 之间变。
            //
            // 之前直接 `size(if (on) 14.dp else 12.dp)`：只要录过一位，这一行的高度就从
            // 12dp 变成 14dp，下面的提示行与整个数字键盘被往下顶 2dp —— 连续输入时看起来
            // 就是键盘在上下抖（用户 2026-10-29 反馈）。槽位固定即可根治。
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(if (on) 14.dp else 10.dp)
                        .clip(CircleShape)
                        .background(if (on) scheme.primary else app.surface3)
                        .then(if (on) Modifier else Modifier.border(1.dp, app.border, CircleShape)),
                )
            }
        }
    }
}

/**
 * 自绘数字键盘（3×4）。
 *
 * 为什么不用系统输入法：见 [PinDots]。这里也不提供「粘贴」之类的入口 ——
 * 剪贴板在隐私模式下同样是泄露面。
 *
 * [enabled] 为 false 时整体变灰并吞掉点击（提交中防连点）。
 */
@Composable
fun PinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { digit ->
                    PinKey(
                        label = digit.toString(),
                        enabled = enabled,
                        onClick = { onDigit(digit) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // 左下留空：0 居中、退格在右，与系统拨号盘的肌肉记忆一致
            Spacer(Modifier.width(KEY_WIDTH))
            PinKey(label = "0", enabled = enabled, onClick = { onDigit('0') })
            PinKey(label = "删除", enabled = enabled, onClick = onBackspace)
        }
    }
}

private val KEY_WIDTH = 72.dp
private val KEY_HEIGHT = 56.dp

@Composable
private fun PinKey(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .pressScale(interaction)
            .width(KEY_WIDTH)
            .height(KEY_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) app.surface2 else app.surface3)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            // 「删除」用正文字号、数字用大字号；同一套颜色规则
            fontSize = if (label.length > 1) 14.sp else 24.sp,
            color = if (enabled) scheme.onSurface else app.text3,
            textAlign = TextAlign.Center,
            style = if (label.length > 1) {
                MaterialTheme.typography.labelLarge
            } else {
                MaterialTheme.typography.headlineSmall
            },
        )
    }
}
