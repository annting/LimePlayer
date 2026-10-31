package app.myplayer.feature.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.AppShape
import app.myplayer.core.designsystem.Dimens
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.MyPlayerTheme
import app.myplayer.core.designsystem.Spacing
import app.myplayer.core.designsystem.components.AlertIcon
import app.myplayer.core.designsystem.components.AppButton
import app.myplayer.core.designsystem.components.AppButtonStyle
import app.myplayer.core.designsystem.components.CheckIcon
import app.myplayer.core.designsystem.components.PageHeader
import app.myplayer.core.designsystem.components.SettingsGroup
import app.myplayer.core.designsystem.components.SettingsSwitchRow
import app.myplayer.core.designsystem.components.SettingsInputRow
import app.myplayer.core.designsystem.components.SettingsMenuRow
import app.myplayer.core.designsystem.components.SettingsOption

/**
 * 添加 / 修改服务器（二级页）。
 *
 * 结构（`docs/03-UI设计规范.md` §9）：顶栏 → 三段分组卡片 → 底部固定操作区。
 * ```
 * 基础信息       服务器名称(仅编辑) / 服务端类型 ▾ / 协议 ▾
 * 网络与路径     服务器地址 / 端口 [/ 域 / 共享目录]   ← SMB 多两行
 * 身份验证       认证方式 ▾ / 账号 / 密码 / API Key  ← 随认证方式变化
 * ```
 * 视觉口径：
 * - 分组卡 **12dp**、行高 56dp、发丝分隔线；页面底 `bg`、卡片 `surface`
 * - **所有选择项都是 DropdownMenu**，页面上没有任何平铺 Chip
 * - 输入框嵌在列表行内：40dp、**8dp**、`surface2` 底、默认无边框、聚焦 1.5dp 品牌色
 * - 主按钮 Filled（青柠绿），次按钮 Outlined，底部**左右并排** 0.4 / 0.6
 *
 * 交互：
 * - 切服务端类型 → 协议回首选值、端口按新类型默认值适配、域与共享目录清空（[AddServerViewModel.onSelectKind]）
 * - 地址框容忍粘贴 `http://…` 与 `host:port`，自动纠偏协议、自动拆端口
 * - 主按钮文案：只改名 =「保存」，其余 =「登录」（本页直接登录，不再有二次登录页）
 */
@Composable
fun AddServerScreen(
    state: AddServerUiState,
    form: AddServerForm,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isEdit: Boolean = state.isEditing,
) {
    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = if (isEdit) "修改服务器" else "添加服务器",
            subtitle = if (isEdit) "改地址或类型后需重新登录" else "地址 + 账号，一次完成接入",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = Dimens.pagePadding,
                end = Dimens.pagePadding,
                top = Spacing.xs.dp,
                bottom = Spacing.lg.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
        ) {
            item(key = "basic") { BasicInfoGroup(state, form, isEdit) }
            item(key = "network") { NetworkGroup(state, form) }
            item(key = "auth") { AuthGroup(state, form) }
            // 「存为隐私源」只在隐私模式下、且是新增时出现（存量源的挪动走设置页「移入隐私」）
            if (state.allowPrivate && !isEdit) {
                item(key = "privacy") { PrivacyGroup(state, form) }
            }
            item(key = "note") { Footnote(state) }
        }
        BottomActionBar(state, form)
    }
}

/** 页面全部回调。收成一个不可变对象，[AddServerScreen] 的签名才不会长成一排。 */
@Immutable
data class AddServerForm(
    val onSelectKind: (ServerKind) -> Unit = {},
    val onSelectProtocol: (NetProtocol) -> Unit = {},
    val onAddressChange: (String) -> Unit = {},
    val onPortChange: (String) -> Unit = {},
    val onDomainChange: (String) -> Unit = {},
    val onShareChange: (String) -> Unit = {},
    val onAuthModeChange: (AuthMode) -> Unit = {},
    val onUsernameChange: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onApiKeyChange: (String) -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onSaveAsPrivateChange: (Boolean) -> Unit = {},
    val onTest: () -> Unit = {},
    val onSubmit: () -> Unit = {},
)

// ---- 分组 1：基础信息 ----

@Composable
private fun BasicInfoGroup(state: AddServerUiState, form: AddServerForm, isEdit: Boolean) {
    SettingsGroup(title = "基础信息") {
        if (isEdit) {
            SettingsInputRow(
                title = "服务器名称",
                value = state.name,
                onValueChange = form.onNameChange,
                placeholder = "我的 ${state.kind.label}",
                imeAction = ImeAction.Next,
            )
        }
        // 极简：只有主标题 + 右侧下拉值，副标题（默认端口等）不在这里出现
        SettingsMenuRow(
            title = "服务端类型",
            options = ServerKind.entries.map { SettingsOption(it, it.label) },
            selected = state.kind,
            onSelect = form.onSelectKind,
        )
        // 协议：SMB 类型固定 SMB（锁定不可点），其余可选 HTTP / HTTPS
        SettingsMenuRow(
            title = "协议",
            options = state.kind.protocols.map { SettingsOption(it, it.label) },
            selected = state.protocol.takeIf { it in state.kind.protocols } ?: state.kind.protocols.first(),
            onSelect = form.onSelectProtocol,
            enabled = !state.kind.protocolLocked,
            showDivider = false,
        )
    }
}

// ---- 分组 2：网络与路径（SMB 多出域 / 共享目录） ----

@Composable
private fun NetworkGroup(state: AddServerUiState, form: AddServerForm) {
    val fields = state.networkFields
    SettingsGroup(title = "网络与路径") {
        fields.forEachIndexed { index, field ->
            val last = index == fields.lastIndex
            when (field) {
                NetworkField.Address -> SettingsInputRow(
                    title = "服务器地址",
                    value = state.address,
                    onValueChange = form.onAddressChange,
                    placeholder = if (state.isSmb) "192.168.31.15" else "192.168.31.15 或 nas.local",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    showDivider = !last,
                )
                NetworkField.Port -> SettingsInputRow(
                    title = "端口",
                    value = state.port,
                    onValueChange = form.onPortChange,
                    placeholder = state.kind.defaultPort.toString(),
                    keyboardType = KeyboardType.Number,
                    imeAction = if (last) ImeAction.Done else ImeAction.Next,
                    showDivider = !last,
                )
                NetworkField.Domain -> SettingsInputRow(
                    title = "域",
                    value = state.domain,
                    onValueChange = form.onDomainChange,
                    placeholder = "可选 · 如 WORKGROUP",
                    imeAction = ImeAction.Next,
                    showDivider = !last,
                )
                NetworkField.Share -> SettingsInputRow(
                    title = "共享目录",
                    value = state.share,
                    onValueChange = form.onShareChange,
                    placeholder = "必填 · 如 media",
                    imeAction = ImeAction.Done,
                    showDivider = !last,
                )
            }
        }
    }
}

// ---- 分组 3：身份验证（随认证方式变化） ----

@Composable
private fun AuthGroup(state: AddServerUiState, form: AddServerForm) {
    val fields = state.authFields
    SettingsGroup(title = "身份验证") {
        fields.forEachIndexed { index, field ->
            val last = index == fields.lastIndex
            when (field) {
                AuthField.Mode -> SettingsMenuRow(
                    title = "认证方式",
                    options = state.kind.authModes.map { SettingsOption(it, it.label) },
                    selected = state.authMode,
                    onSelect = form.onAuthModeChange,
                    showDivider = !last,
                )
                AuthField.Username -> SettingsInputRow(
                    title = "账号",
                    value = state.username,
                    onValueChange = form.onUsernameChange,
                    placeholder = if (state.isSmb) "如 guest" else "Emby / Jellyfin 账号",
                    imeAction = ImeAction.Next,
                    showDivider = !last,
                )
                AuthField.Password -> SettingsInputRow(
                    title = "密码",
                    value = state.password,
                    onValueChange = form.onPasswordChange,
                    placeholder = if (state.isSmb) "guest 可留空" else "服务端密码",
                    isPassword = true,
                    imeAction = ImeAction.Done,
                    showDivider = !last,
                )
                AuthField.ApiKey -> SettingsInputRow(
                    title = "API Key",
                    value = state.apiKey,
                    onValueChange = form.onApiKeyChange,
                    placeholder = "服务端「API 密钥」页生成",
                    isPassword = true,
                    imeAction = ImeAction.Done,
                    showDivider = !last,
                )
            }
        }
    }
}

// ---- 分组 4：隐私（仅隐私模式下新增时出现） ----

@Composable
private fun PrivacyGroup(state: AddServerUiState, form: AddServerForm) {
    SettingsGroup(title = "隐私") {
        SettingsSwitchRow(
            title = "存为隐私源",
            description = "普通模式下不显示这台服务器：不列举、不连接、地址与账号只存在加密区。",
            checked = state.saveAsPrivate,
            onCheckedChange = form.onSaveAsPrivateChange,
            showDivider = false,
        )
    }
}

@Composable
private fun Footnote(state: AddServerUiState) {
    val app = LocalAppColors.current
    // 说明文字压低存在感：12sp + text3，不与表单争视觉重心
    val footnoteStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal)
    Column {
        Text(
            text = "凭据仅加密保存在本机（Android Keystore），不上传；支持 HTTP 与自签证书地址。",
            style = footnoteStyle,
            color = app.text3,
        )
        if (state.isSmb) {
            Spacer(Modifier.height(Spacing.xs.dp))
            Text(
                text = "SMB 源模块尚未接入：本页可填写并测试 445 连通性，保存与登录会在后续版本开放。",
                style = footnoteStyle,
                color = app.warning,
            )
        }
    }
}

// ---- 底部固定操作区 ----

/**
 * 底部操作区：提示条在按钮**上方**，按钮左右并排（0.4 / 0.6）。
 *
 * 系统栏安全区由 `MainActivity` 的 `safeDrawing` 统一避让，这里只留 16dp 视觉留白，
 * 不重复加 `navigationBarsPadding()`（否则手势条会被算两次）。
 */
@Composable
private fun BottomActionBar(state: AddServerUiState, form: AddServerForm) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    val hint = bottomHint(state)
    // 点按钮前先收焦点：否则输入框的 1.5dp 聚焦描边会一直亮在已经离开的那一行上
    val focus = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.background),
    ) {
        HorizontalDivider(thickness = Dimens.hairline, color = app.border)
        Column(
            Modifier.padding(
                start = Dimens.pagePadding,
                end = Dimens.pagePadding,
                top = Spacing.md.dp,
                bottom = Spacing.lg.dp,
            ),
        ) {
            if (hint != null) {
                HintBanner(hint)
                Spacer(Modifier.height(Spacing.md.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppButton(
                    text = "测试连接",
                    onClick = {
                        focus.clearFocus()
                        form.onTest()
                    },
                    style = AppButtonStyle.Outlined,
                    enabled = state.canTest,
                    loading = state.isTesting,
                    modifier = Modifier.weight(0.4f),
                )
                AppButton(
                    text = state.submitLabel,
                    onClick = {
                        focus.clearFocus()
                        form.onSubmit()
                    },
                    style = AppButtonStyle.Primary,
                    enabled = state.canSubmit,
                    loading = state.isSubmitting,
                    modifier = Modifier.weight(0.6f),
                )
            }
        }
    }
}

/** 底部提示条：克制的一行，成功态用「深底 + 绿字」。 */
private sealed interface BottomHint {
    val text: String

    data class Success(override val text: String) : BottomHint
    data class Warning(override val text: String) : BottomHint
    data class Error(override val text: String) : BottomHint
}

private fun bottomHint(state: AddServerUiState): BottomHint? {
    val error = state.error
    if (error != null) return BottomHint.Error(error)
    val probe = state.probeStatus
    if (probe is ProbeStatus.Reachable) {
        val name = probe.serverName.takeIf { it.isNotBlank() && it != state.endpointLabel }
        return BottomHint.Success(
            if (name != null) "已连接 · ${state.endpointLabel}（$name）" else "已连接 · ${state.endpointLabel}"
        )
    }
    val reason = state.submitBlockReason
    return if (!state.canSubmit && reason != null) BottomHint.Warning(reason) else null
}

@Composable
private fun HintBanner(hint: BottomHint) {
    val app = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    val accent = when (hint) {
        is BottomHint.Success -> app.success
        is BottomHint.Warning -> app.warning
        is BottomHint.Error -> scheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.control)
            // 语义色 10% 叠在底色上：既表明「这是状态行」，又不至于变成一块彩色卡片。
            // 旧做法是 surface3 实底 + 6dp 圆点，权重比页脚说明还低，根本不像错误提示。
            .background(accent.copy(alpha = 0.10f))
            .heightIn(min = 44.dp)
            .padding(horizontal = Spacing.md.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hint is BottomHint.Success) {
            CheckIcon(tint = accent, modifier = Modifier.size(16.dp))
        } else {
            // 18dp 语义图标替掉原来的 6dp 圆点
            AlertIcon(tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(Spacing.sm.dp))
        Text(
            text = hint.text,
            // 12sp：11.5sp 在 360dp 宽的屏上，中文提示偏小一号
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = accent,
        )
    }
}

// ---- 预览 ----

@Preview(name = "Emby · 暗色", showBackground = true, heightDp = 900)
@Composable
private fun PreviewAddServerEmbyDark() = MyPlayerTheme(darkTheme = true) {
    AddServerScreen(
        state = AddServerUiState(
            address = "192.168.31.15",
            username = "ting.an2",
            password = "••••",
            probeStatus = ProbeStatus.Reachable("客厅 Emby", "Emby Server", "4.8"),
        ),
        form = AddServerForm(),
        onBack = {},
    )
}

@Preview(name = "SMB · 亮色", showBackground = true, heightDp = 900)
@Composable
private fun PreviewAddServerSmbLight() = MyPlayerTheme(darkTheme = false) {
    AddServerScreen(
        state = AddServerUiState(
            kind = ServerKind.SMB,
            protocol = NetProtocol.SMB,
            address = "192.168.31.15",
            port = "445",
            share = "media",
            username = "guest",
        ),
        form = AddServerForm(),
        onBack = {},
    )
}
