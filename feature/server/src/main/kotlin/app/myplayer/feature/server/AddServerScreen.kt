package app.myplayer.feature.server

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 添加服务器（spec §17）：选源卡片 → 地址 + 端口 → 测试连接回显 → 下一步。
 * 选源只有 **Emby 一张卡片**（本机视频属 L2、飞牛登录页在 M4 出现，不做占位入口）。
 */
@Composable
fun AddServerScreen(
    state: AddServerUiState,
    onAddressChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTest: () -> Unit,
    onProceed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Emby", style = MaterialTheme.typography.titleMedium)
                Text(
                    "用 Emby 账号密码登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = state.address,
            onValueChange = onAddressChange,
            label = { Text("服务器地址") },
            placeholder = { Text("192.168.31.10") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.port,
            onValueChange = onPortChange,
            label = { Text("端口") },
            singleLine = true,
            isError = state.error?.contains("端口") == true,
            supportingText = { state.error?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        when (val s = state.probeStatus) {
            is ProbeStatus.Testing -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is ProbeStatus.Reachable -> Text(
                "已连接：${s.serverName}${s.version?.let { " · $it" } ?: ""}",
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Unit
        }
        state.error?.takeIf { !it.contains("端口") }?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.height(48.dp)) { Text("返回") }
            OutlinedButton(onClick = onTest, modifier = Modifier.height(48.dp)) { Text("测试连接") }
            Button(
                onClick = onProceed,
                enabled = state.canProceed,          // 未探测成功不给进下一步（spec §17）
                modifier = Modifier.height(48.dp),
            ) { Text("下一步") }
        }
    }
}
