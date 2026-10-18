package app.myplayer.feature.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppRadius
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.components.AppButton
import app.myplayer.core.designsystem.components.AppButtonStyle
import app.myplayer.core.designsystem.components.AppField
import app.myplayer.core.designsystem.components.CheckIcon
import app.myplayer.core.designsystem.components.FieldIcon
import app.myplayer.core.designsystem.components.PageHeader
import app.myplayer.core.designsystem.components.ServerIcon
import app.myplayer.core.designsystem.components.StepsIndicator

/**
 * 添加服务器（`docs/03` §9 / 设计板 ③⑪）：选源卡片 → 地址 + 端口 → 测试连接回显 → 下一步。
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
    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = "添加服务器",
            subtitle = "先测试连接，再登录",
            onBack = onBack,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StepsIndicator(total = 2, current = 1)
            Spacer(Modifier.height(2.dp))

            SourceCard(
                title = "Emby",
                description = "用 Emby 账号密码登录",
            )

            AppField(
                label = "服务器地址",
                value = state.address,
                onValueChange = onAddressChange,
                placeholder = "192.168.31.15",
                leadingIcon = FieldIcon.Globe,
                keyboardType = KeyboardType.Uri,
            )
            AppField(
                label = "端口",
                value = state.port,
                onValueChange = onPortChange,
                placeholder = "8096",
                leadingIcon = FieldIcon.Server,
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            )

            AppButton(
                text = "测试连接",
                onClick = onTest,
                style = AppButtonStyle.Tonal,
                loading = state.probeStatus is ProbeStatus.Testing,
            )

            when (val probe = state.probeStatus) {
                is ProbeStatus.Reachable -> ProbeOkCard(
                    name = probe.serverName,
                    version = probe.version,
                    product = probe.productName,
                )
                is ProbeStatus.NotFound -> HintLine("地址可达但未识别为 Emby 服务——请检查端口与路径前缀。")
                is ProbeStatus.Unreachable -> HintLine(probe.reason, isError = true)
                else -> Unit
            }
            state.error?.let { HintLine(it, isError = true) }

            AppButton(
                text = "下一步",
                onClick = onProceed,
                enabled = state.canProceed,          // 未探测成功不给进下一步（spec §17）
            )

            Text(
                "服务器凭据仅加密保存在本机（Android Keystore），不上传；支持 HTTP / 自签证书地址。",
                style = MaterialTheme.typography.labelSmall,
                color = LocalAppColors.current.text3,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

/** 选源卡片（设计板 `.source-card`）：品牌柔底图标 + 标题 / 说明。 */
@Composable
private fun SourceCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.sourceCard.dp))
            .background(scheme.surface)
            .clickable(onClick = { /* M1 只有 Emby；M4 增加飞牛卡片时在此切换选中态 */ })
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            ServerIcon(tint = scheme.primary, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = LocalAppColors.current.text3,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 探测成功回显卡（设计板 `.probe-ok`）：对勾徽标 + 服务器名与版本。 */
@Composable
private fun ProbeOkCard(name: String, version: String?, product: String?) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            CheckIcon(tint = scheme.primary, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text("已连接 · $name", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            val meta = listOfNotNull(
                version?.let { "版本 $it" },
                product?.let { "协议 $it" },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalAppColors.current.text3,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun HintLine(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (isError) MaterialTheme.colorScheme.error else LocalAppColors.current.text3,
    )
}
