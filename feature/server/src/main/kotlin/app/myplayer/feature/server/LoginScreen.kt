package app.myplayer.feature.server

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.components.AppButton
import app.myplayer.core.designsystem.components.AppField
import app.myplayer.core.designsystem.components.FieldIcon
import app.myplayer.core.designsystem.components.PageHeader
import app.myplayer.core.designsystem.components.StepsIndicator

/**
 * 登录页（`docs/03` §9 / 设计板 ④⑪）：账号 + 密码 + 登录；`SUCCESS` 时通过 [onLoggedIn] 通知导航。
 * 标题按所选源变化（Emby / Jellyfin / 飞牛影视），登录实现由调用方按源注入。
 * **不显示**「NAS 账号授权 / 扫码登录」分段控件（spec §17：不做占位、不做置灰入口）。
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier,
    serverLabel: String? = null,
    sourceName: String = "Emby",
    onBack: (() -> Unit)? = null,
) {
    LaunchedEffect(state.status) {
        if (state.status == LoginStatus.SUCCESS) onLoggedIn()
    }
    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = "登录 $sourceName",
            subtitle = serverLabel?.let { "连接 $it" } ?: "输入服务器账号",
            onBack = onBack,
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StepsIndicator(total = 2, current = 2)
            Spacer(Modifier.height(2.dp))

            AppField(
                label = "账号",
                value = state.username,
                onValueChange = onUsernameChange,
                placeholder = "账号或邮箱",
                leadingIcon = FieldIcon.Person,
                enabled = state.status != LoginStatus.SUBMITTING,
            )
            AppField(
                label = "密码",
                value = state.password,
                onValueChange = onPasswordChange,
                placeholder = "密码",
                leadingIcon = FieldIcon.Lock,
                isPassword = true,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                enabled = state.status != LoginStatus.SUBMITTING,
            )

            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            AppButton(
                text = "登录",
                onClick = onSubmit,
                loading = state.status == LoginStatus.SUBMITTING,
                enabled = state.status != LoginStatus.SUBMITTING,
            )

            Text(
                "凭据仅加密保存在本机（Android Keystore），不上传；支持 HTTP / 自签证书地址。",
                style = MaterialTheme.typography.labelSmall,
                color = LocalAppColors.current.text3,
            )
        }
    }
}
