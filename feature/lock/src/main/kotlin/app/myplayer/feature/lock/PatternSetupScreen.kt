package app.myplayer.feature.lock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.myplayer.core.security.PatternCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI 测试用：整个设置页的根节点。 */
const val PatternSetupScreenTestTag = "pattern-setup"

/**
 * 设置 / 重设解锁图案（`设置 → 隐私 → 解锁图案`）。
 *
 * 两遍输入：第一遍挑图案（≥4 点、非弱图案），第二遍确认；一致才落库。
 * 复用 [PatternLockScreen] 的九宫格与动效，只换标题与顶部「取消」。
 *
 * 注意：`savePattern` 是 PBKDF2（12 万次迭代），必须离开主线程。
 */
@Composable
fun PatternSetupScreen(
    credentials: PatternCredentialStore,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = remember {
        // 已经录过图案 → 先验证旧的（防止拿着已解锁的手机直接改掉锁屏）
        PatternSetupController(
            verifier = if (credentials.isConfigured()) credentials::verifyPattern else null,
        )
    }
    var stage by remember { mutableStateOf(controller.stage) }
    var selected by remember { mutableStateOf(emptyList<Int>()) }
    var message by remember {
        mutableStateOf(
            if (controller.stage == PatternSetupStage.VERIFY) "请先绘制当前图案" else null,
        )
    }
    var errorFlash by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val state = LockUiState(
        status = when {
            saving -> LockStatus.VERIFYING
            errorFlash -> LockStatus.ERROR
            selected.isEmpty() -> LockStatus.IDLE
            else -> LockStatus.DRAWING
        },
        selected = selected,
        message = when {
            saving -> "保存中…"
            else -> message
        },
    )

    Box(modifier.fillMaxSize().testTag(PatternSetupScreenTestTag)) {
        PatternLockScreen(
            state = state,
            title = when (stage) {
                PatternSetupStage.VERIFY -> "验证原有图案"
                PatternSetupStage.CONFIRM -> "确认解锁图案"
                PatternSetupStage.FIRST -> "设置解锁图案"
            },
            onPointTouched = { index ->
                if (!saving && !errorFlash && index !in selected) selected = selected + index
            },
            onDrawEnd = {
                if (saving || errorFlash) return@PatternLockScreen
                val points = selected
                if (points.isEmpty()) {
                    // 抬手但一个点都没命中：什么都不做，别弹出「至少 4 个点」吓人
                } else {
                    when (val outcome = controller.submit(points)) {
                        is PatternSetupOutcome.Reject -> {
                            message = outcome.message
                            errorFlash = true          // 触发抖动，动画结束再清空
                        }
                        is PatternSetupOutcome.Verified -> {
                            stage = controller.stage
                            message = outcome.message
                            selected = emptyList()
                        }
                        is PatternSetupOutcome.Confirm -> {
                            stage = controller.stage
                            message = outcome.message
                            selected = emptyList()
                        }
                        is PatternSetupOutcome.Save -> {
                            saving = true
                            scope.launch {
                                withContext(Dispatchers.Default) {
                                    credentials.savePattern(outcome.pattern, backupPin = null)
                                }
                                onDone()
                            }
                        }
                    }
                }
            },
            onErrorAnimationDone = {
                errorFlash = false
                selected = emptyList()
            },
        )

        TextButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp),
        ) {
            Text(
                text = "取消",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
