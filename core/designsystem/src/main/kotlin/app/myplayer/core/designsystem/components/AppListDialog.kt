package app.myplayer.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.Spacing

/**
 * 列表型弹窗外壳（**与 [AppConfirmDialog] 同一套容器口径**）。
 *
 * 存在原因（`docs/相似组件审查报告.md` 组 E）：`ManageRootsDialog` 是"标题 + 长列表 + 关闭"
 * 的弹窗，套不进 [AppConfirmDialog] 的"标题 + 一段正文 + 两个按钮"，于是裸用 M3 `AlertDialog`——
 * 结果圆角（20 vs M3 默认）、四边内边距（20 vs 24）、内容↔按钮（12 vs 24）全错开一档。
 *
 * 本组件只统一**外壳**：圆角 20dp、四边 [Dimens.dialogPadding]、标题→内容 [Spacing.sm]、
 * 内容→按钮行 [Spacing.md]、按钮行内间距 [Spacing.xs]。列表内部由调用方排。
 *
 * @param content 列表内容。需要滚动时由调用方在 content 里套 `verticalScroll`（本组件不强制
 *   高度，因为不同弹窗的可用高度不一样）
 */
@Composable
fun AppListDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String = "关闭",
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier
                .padding(horizontal = 40.dp)
                .widthIn(max = 340.dp)
                .fillMaxWidth(),
            shape = AppShape.dialog,
            color = scheme.surface,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(Dimens.dialogPadding)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.sm.dp))
                content()
                Spacer(Modifier.height(Spacing.md.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = ListDialogButtonPadding,
                    ) {
                        Text(
                            text = dismissText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = app.text3,
                        )
                    }
                }
            }
        }
    }
}

/** 与 `AppConfirmDialog` 的按钮内边距同口径：横向 12dp、纵向 8dp。 */
private val ListDialogButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
