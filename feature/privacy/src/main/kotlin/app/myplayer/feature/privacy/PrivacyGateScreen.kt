package app.myplayer.feature.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.components.AppButton
import app.myplayer.core.designsystem.components.AppButtonStyle
import app.myplayer.core.designsystem.components.PageHeader
import app.myplayer.core.security.privacy.PrivacyRecoveryRisk
import kotlinx.coroutines.launch

/**
 * 隐私门的六个阶段。**只在门内部有效** —— 门外（普通模式）不存在「半开」状态。
 */
private enum class Stage {
    /** 已配置过：输 PIN 解锁（进门前会先自动试一次指纹）。 */
    Unlock,

    /** 首次设置：设定 PIN。 */
    SetupPin,

    /** 首次设置：再输一次确认（避免设错一个数字就把自己锁在外面）。 */
    SetupConfirm,

    /** 首次设置：问要不要把指纹一起封进保险箱。 */
    SetupBiometrics,

    /** 忘 PIN：用设备密码/指纹解封后，设一个新 PIN。 */
    RecoverPin,

    /** 忘 PIN：确认新 PIN。 */
    RecoverConfirm,
}

/**
 * 隐私模式的全屏门（`docs/隐私模式方案.md` §P0#5）。
 *
 * 三种入口共用这一个界面：
 * - **首次设置**（保险箱还不存在）：设 PIN → 确认 → 问指纹 → 创建；
 * - **日常进入**（已配置）：自动试指纹，点弹框的「改用隐私 PIN」回到这里输 PIN；
 * - **忘 PIN**：用设备密码钥匙解封 DEK → 换新 PIN，**隐私数据全部保留**。
 *
 * 界面本身不做任何鉴权判断，全部委托给 [PrivacyAccessCoordinator] —— 这里只负责
 * 把「用户按了什么」翻译成「调哪个方法」，以及把结果翻译成文案。
 *
 * 防窥：整个界面在隐私模式下会随 `FLAG_SECURE` 一起禁截图；门口（`Unlock`）阶段
 * 不展示任何隐私内容的痕迹（没有源名、没有条目数），只有一枚锁形标题。
 */
@Composable
fun PrivacyGateScreen(
    coordinator: PrivacyAccessCoordinator,
    onEntered: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    val scope = rememberCoroutineScope()

    var stage by remember {
        mutableStateOf(if (coordinator.isConfigured()) Stage.Unlock else Stage.SetupPin)
    }
    var pin by remember { mutableStateOf("") }
    /** 首次设置 / 恢复流程里第一次输入的 PIN，等确认。 */
    var pendingPin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun fail(text: String) {
        pin = ""
        message = text
    }

    fun applyAccessResult(result: PrivacyAccessResult) {
        when (result) {
            PrivacyAccessResult.Success -> onEntered()
            PrivacyAccessResult.Cancelled -> {
                pin = ""
                message = null
            }

            is PrivacyAccessResult.Unavailable -> fail(result.reason.message)
            is PrivacyAccessResult.Failed -> fail(result.message)
        }
    }

    /** 首次设置的收尾：建保险箱；失败就退回设 PIN 阶段并说明原因。 */
    suspend fun finishSetup(value: String, enableBiometrics: Boolean) {
        when (val result = coordinator.setup(value.toCharArray(), enableBiometrics)) {
            is SetupResult.Created -> onEntered()
            is SetupResult.Failed -> {
                stage = Stage.SetupPin
                pendingPin = ""
                fail(result.message)
            }
        }
    }

    fun submit(value: String) {
        scope.launch {
            busy = true
            message = null
            try {
                when (stage) {
                    Stage.Unlock -> applyAccessResult(coordinator.enterWithPin(value.toCharArray()))

                    Stage.SetupPin -> {
                        pendingPin = value
                        pin = ""
                        stage = Stage.SetupConfirm
                    }

                    Stage.SetupConfirm -> {
                        when {
                            value != pendingPin -> {
                                pendingPin = ""
                                stage = Stage.SetupPin
                                fail("两次输入不一致，请重新设置")
                            }

                            // 本机没有可用指纹（没录指纹 / 版本过低）：跳过询问，
                            // 直接建纯 PIN 保险箱 —— 不让用户点一个必然失败的按钮
                            !coordinator.capability.biometric -> {
                                pin = ""
                                finishSetup(value, enableBiometrics = false)
                            }

                            else -> {
                                pin = ""
                                stage = Stage.SetupBiometrics
                            }
                        }
                    }

                    Stage.SetupBiometrics -> Unit // 由按钮驱动

                    Stage.RecoverPin -> {
                        pendingPin = value
                        pin = ""
                        stage = Stage.RecoverConfirm
                    }

                    Stage.RecoverConfirm -> {
                        if (value == pendingPin) {
                            applyAccessResult(coordinator.recover(value.toCharArray()))
                        } else {
                            pendingPin = ""
                            pin = ""
                            stage = Stage.RecoverPin
                            fail("两次输入不一致，请重新设置")
                        }
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    fun push(digit: Char) {
        if (busy || pin.length >= PRIVACY_PIN_LENGTH) return
        val next = pin + digit
        pin = next
        if (next.length == PRIVACY_PIN_LENGTH) submit(next)
    }

    // 进门先自动试一次指纹：有指纹的用户连 PIN 都不用输。
    // 只跑一次（key = Unit），失败/取消后由用户自己决定要不要再点。
    LaunchedEffect(Unit) {
        if (stage == Stage.Unlock && coordinator.canUseBiometrics()) {
            busy = true
            val result = coordinator.enterWithBiometrics()
            busy = false
            when (result) {
                PrivacyAccessResult.Success -> onEntered()
                PrivacyAccessResult.Cancelled -> Unit
                // 指纹硬件出问题时不要打断用户，退到 PIN 更实际
                is PrivacyAccessResult.Unavailable -> Unit
                is PrivacyAccessResult.Failed -> message = result.message
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        PageHeader(
            title = "隐私模式",
            subtitle = when (stage) {
                Stage.Unlock -> "验证后查看隐私内容"
                Stage.SetupPin -> "首次使用 · 设置隐私 PIN"
                Stage.SetupConfirm -> "首次使用 · 再输一次"
                Stage.SetupBiometrics -> "首次使用 · 快速进入"
                Stage.RecoverPin -> "重设隐私 PIN"
                Stage.RecoverConfirm -> "重设隐私 PIN · 再输一次"
            },
            onBack = onDismiss,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))

            Text(
                text = headlineFor(stage),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = hintFor(stage),
                style = MaterialTheme.typography.bodySmall,
                color = app.text3,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))
            PinDots(filled = pin.length)

            // 错误/提示固定占一行，避免出现时把键盘顶得上下跳
            Spacer(Modifier.height(12.dp))
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (message != null) scheme.error else app.text3,
                textAlign = TextAlign.Center,
                // 空串与一行文字的测量高度不一定相同（空文本可能量出 0 高），
                // 给个下限才能保证「有没有提示」都不动下面键盘的位置
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 20.dp),
            )

            Spacer(Modifier.height(20.dp))

            if (stage == Stage.SetupBiometrics) {
                BiometricsChoice(
                    capabilityHint = biometricHint(coordinator),
                    busy = busy,
                    onEnable = {
                        scope.launch {
                            busy = true
                            try {
                                finishSetup(pendingPin, enableBiometrics = true)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onSkip = {
                        scope.launch {
                            busy = true
                            try {
                                finishSetup(pendingPin, enableBiometrics = false)
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
            } else {
                PinPad(
                    onDigit = ::push,
                    onBackspace = { if (!busy && pin.isNotEmpty()) pin = pin.dropLast(1) },
                    enabled = !busy,
                )

                if (stage == Stage.Unlock) {
                    Spacer(Modifier.height(16.dp))
                    // 恢复钥匙状态（方案 §7.5）：让用户清楚现在还有哪些后路
                    Text(
                        text = boundKeysHint(coordinator),
                        style = MaterialTheme.typography.labelMedium,
                        color = app.text3,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "忘记隐私 PIN？",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                        modifier = Modifier
                            .clickable {
                                pin = ""
                                pendingPin = ""
                                message = null
                                stage = Stage.RecoverPin
                            }
                            .padding(8.dp),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BiometricsChoice(
    capabilityHint: String,
    busy: Boolean,
    onEnable: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = capabilityHint,
            style = MaterialTheme.typography.bodySmall,
            color = LocalAppColors.current.text3,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        AppButton(
            text = "开启指纹快速进入",
            onClick = onEnable,
            enabled = !busy,
            loading = busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        AppButton(
            text = "只用 PIN",
            onClick = onSkip,
            style = AppButtonStyle.Outlined,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun headlineFor(stage: Stage): String = when (stage) {
    Stage.Unlock -> "输入隐私 PIN"
    Stage.SetupPin -> "设置 6 位隐私 PIN"
    Stage.SetupConfirm -> "再次输入以确认"
    Stage.SetupBiometrics -> "是否开启指纹快速进入？"
    Stage.RecoverPin -> "设置新的隐私 PIN"
    Stage.RecoverConfirm -> "再次输入新 PIN"
}

private fun hintFor(stage: Stage): String = when (stage) {
    Stage.Unlock -> "隐私 PIN 与日常应用锁独立，只用于隐私模式"
    Stage.SetupPin -> "忘记后可用指纹或设备密码恢复；没有可用钥匙时数据无法找回"
    Stage.SetupConfirm -> "两次输入需完全一致"
    Stage.SetupBiometrics -> "指纹只保存在本机安全芯片里，不会上传"
    Stage.RecoverPin -> "验证指纹或设备密码后即可重设，隐私数据不会丢失"
    Stage.RecoverConfirm -> "两次输入需完全一致"
}

/** 「本机现在靠什么能进」——门口的三态提示（PIN 恒有，指纹 / 设备密码看绑定情况）。 */
private fun boundKeysHint(coordinator: PrivacyAccessCoordinator): String {
    val keys = coordinator.boundKeys()
    val names = buildList {
        add("PIN")
        if ("bio" in keys) add("指纹")
        if ("device" in keys) add("设备密码")
    }
    return "可用方式：${names.joinToString(" · ")}"
}

private fun biometricHint(coordinator: PrivacyAccessCoordinator): String {
    val capability = coordinator.capability
    return when {
        // 有设备密码作后路，但没录指纹：只能说设备密码，别承诺「支持指纹」
        capability.recoveryRisk == PrivacyRecoveryRisk.RECOVERABLE && capability.biometric ->
            "本机支持指纹与设备密码，忘记 PIN 也能无损找回"

        capability.recoveryRisk == PrivacyRecoveryRisk.RECOVERABLE ->
            "忘记 PIN 可用设备密码无损找回"

        capability.recoveryRisk == PrivacyRecoveryRisk.BIOMETRIC_ONLY ->
            "本机只能用指纹恢复；重启后指纹不可用，请务必记住 PIN"

        else ->
            "本机没有可用的恢复方式，忘记 PIN 将无法找回隐私数据，请务必牢记"
    }
}
