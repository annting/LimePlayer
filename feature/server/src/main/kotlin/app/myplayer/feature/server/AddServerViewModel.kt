package app.myplayer.feature.server

import app.myplayer.core.datastore.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** 探测结果与探测状态合为一个类型，避免 ProbeResult / ProbeStatus 两份模型来回映射。 */
sealed interface ProbeStatus {
    data object Idle : ProbeStatus
    data object Testing : ProbeStatus
    data class Reachable(val serverName: String, val productName: String?, val version: String?) : ProbeStatus
    data object NotFound : ProbeStatus
    data class Unreachable(val reason: String) : ProbeStatus
}

/** 提交（保存 / 登录）的进行态；[Done] 由页面据此跳转。 */
enum class SubmitStatus { Idle, Running, Done }

/**
 * 「添加 / 修改服务器」页的全部状态。
 *
 * 页面结构（`docs/03-UI设计规范.md` §9）三个分组：
 * 基础信息（名称 / 服务端类型 / 协议）· 网络与路径（地址 / 端口 / 域 / 共享目录）· 身份验证。
 * 哪些行出现由 [kind] 与 [authMode] 决定，见 [networkFields] / [authFields]。
 */
data class AddServerUiState(
    /** 服务端类型：决定默认端口、可选协议、认证方式与是否要共享目录。 */
    val kind: ServerKind = ServerKind.EMBY,
    /** 协议（SMB 类型固定为 [NetProtocol.SMB] 且不可点）。 */
    val protocol: NetProtocol = NetProtocol.HTTP,
    val address: String = "",
    /** 端口：空串表示「不写端口」（编辑一个原 URL 不带端口的服务器时保持原样）。 */
    val port: String = ServerKind.EMBY.defaultPort.toString(),
    /** 路径前缀（`/emby` 这类反代前缀）：不单独显示，但编辑时必须原样带回去。 */
    val path: String? = null,
    /** SMB 的域（可选）。 */
    val domain: String = "",
    /** SMB 的共享目录（必填，不带前导斜杠）。 */
    val share: String = "",
    val authMode: AuthMode = AuthMode.PASSWORD,
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
    /** 服务器显示名：仅编辑模式可改；新增时由登录返回的账号名决定。 */
    val name: String = "",
    val probeStatus: ProbeStatus = ProbeStatus.Idle,
    val submitStatus: SubmitStatus = SubmitStatus.Idle,
    val error: String? = null,
    /** 编辑模式下的原始配置；null = 新增服务器（同时作为「是否编辑」的判据）。 */
    val original: ServerConfig? = null,
    /**
     * 存为隐私源（`docs/隐私模式方案.md` §7.1 第 6 步）。
     *
     * 只有隐私模式下才允许打开（[allowPrivate]）；列表里看不到的服务器不可能被标成隐私。
     */
    val saveAsPrivate: Boolean = false,
    /** 当前是否处于隐私模式 —— 决定「存为隐私源」这一行是否出现。 */
    val allowPrivate: Boolean = false,
) {
    val isEditing: Boolean get() = original != null
    val isSmb: Boolean get() = kind.isSmb
    val isTesting: Boolean get() = probeStatus is ProbeStatus.Testing
    val isSubmitting: Boolean get() = submitStatus == SubmitStatus.Running
    val probeReachable: Boolean get() = probeStatus is ProbeStatus.Reachable

    /** 「网络与路径」分组的行序 —— 直接驱动 UI，避免页面里散落 `if (isSmb)`。 */
    val networkFields: List<NetworkField> get() = networkFieldOrder(kind)

    /** 「身份验证」分组的行序（随认证方式动态变化）。 */
    val authFields: List<AuthField> get() = authFieldOrder(kind, authMode)

    /**
     * 归一化后的目标地址：探测、保存、登录三处都用它，避免算法漂移。
     *
     * SMB 的共享目录拼在路径段：`smb://nas:445/media`。
     */
    val targetUrl: String
        get() {
            val path = if (isSmb) share.trim().trim('/').takeIf { it.isNotEmpty() }?.let { "/$it" } else path
            return buildServerUrl(protocol, address, port, path)
        }

    /** 展示用：去掉 scheme 与路径的 `host:port`（提示条文案用）。 */
    val endpointLabel: String
        get() {
            val host = address.trim().substringAfter("://").substringBefore('/')
            val p = port.trim()
            return if (p.isEmpty()) host else "$host:$p"
        }

    /** 端点（协议 / 地址 / 端口 / 路径 / 源类型）是否变动 —— 变动后旧令牌失效，必须重新登录。 */
    val endpointChanged: Boolean
        get() {
            val o = original ?: return false
            return o.baseUrl != targetUrl || o.sourceType != kind.sourceType
        }

    /** 凭据是否填齐（决定「保存 / 登录」能不能点）。 */
    val credentialsReady: Boolean
        get() = when {
            isSmb -> username.isNotBlank()
            authMode == AuthMode.API_KEY -> apiKey.isNotBlank()
            authMode == AuthMode.NO_PASSWORD -> username.isNotBlank()
            else -> username.isNotBlank() && password.isNotBlank()
        }

    /** 主按钮文案：只改名称是「保存」，其余都需要真的登录一次。 */
    val submitLabel: String
        get() = if (isEditing && !endpointChanged) "保存" else "登录"

    /** 「测试连接」可用：有一个地址就能探。 */
    val canTest: Boolean get() = address.isNotBlank() && !isTesting

    /**
     * 主按钮可用。
     *
     * - 源模块未接入（SMB）：恒 false，页面上给明确提示，不做「点了没反应」的死按钮
     * - 编辑且端点没动：只改名称，免探测免登录
     * - 其余：字段齐 + 探测通过（`spec §17`：未确认可达不往下走）
     */
    val canSubmit: Boolean
        get() = when {
            !kind.isPlayable -> false
            isSubmitting -> false
            isEditing && !endpointChanged -> name.isNotBlank()
            else -> address.isNotBlank() &&
                (if (isSmb) share.isNotBlank() else true) &&
                credentialsReady &&
                probeReachable
        }

    /**
     * 主按钮为什么不能点 —— 直接显示在按钮上方，比置灰不给理由友好。
     * 返回 null 表示可以点。
     */
    val submitBlockReason: String?
        get() = when {
            !kind.isPlayable -> "SMB 直连尚未接入：可以先填写并测试 445 端口，保存与登录会在后续版本开放。"
            isSubmitting -> null
            isEditing && !endpointChanged -> if (name.isBlank()) "请填写服务器名称" else null
            address.isBlank() -> "请填写服务器地址"
            isSmb && share.isBlank() -> "请填写共享目录"
            !credentialsReady -> when {
                isSmb || authMode == AuthMode.NO_PASSWORD -> "请填写账号"
                authMode == AuthMode.API_KEY -> "请填写 API Key"
                else -> "请填写账号与密码"
            }
            !probeReachable -> "请先点「测试连接」确认地址可达"
            else -> null
        }
}

class AddServerViewModel(
    /** 生产环境按类型分派到 `probeServer` / `probeFnos` / `probeTcp`；测试传假实现。 */
    private val probe: suspend (ServerKind, String) -> ProbeStatus = { _, _ -> ProbeStatus.Idle },
    /** 登录 + 落盘；测试传假实现。 */
    private val auth: ServerAuthGateway = ServerAuthGateway { SignInResult.Failed("登录通道未接入") },
    /** 编辑模式下「只改名」的本地落盘（不需要重新登录）。 */
    private val saveLocal: suspend (name: String) -> Unit = {},
    initialKind: ServerKind = ServerKind.EMBY,
    /** 编辑模式：预填整份配置；为 null 表示新增服务器。 */
    initial: ServerConfig? = null,
    /** 新增时先分配 id：重试登录时沿用同一个 id，不会写出一堆半成品配置。 */
    private val serverId: String = UUID.randomUUID().toString(),
    /** 是否处于隐私模式：为 true 时页面才显示「存为隐私源」开关。 */
    allowPrivate: Boolean = false,
) {
    private val _state = MutableStateFlow(
        if (initial == null) {
            AddServerUiState(
                kind = initialKind,
                protocol = initialKind.protocols.first(),
                port = initialKind.defaultPort.toString(),
                allowPrivate = allowPrivate,
            )
        } else {
            val parsed = parseServerUrl(initial.baseUrl)
            val kind = ServerKind.of(initial.sourceType)
            val protocol = parsed.protocol?.takeIf { it in kind.protocols } ?: kind.protocols.first()
            AddServerUiState(
                kind = kind,
                protocol = protocol,
                address = parsed.host,
                // 原 URL 没有端口 → 端口框留空，保存时也不补：否则「只改名」会被判成端点改动
                port = parsed.port.orEmpty(),
                path = parsed.path,
                name = initial.name,
                original = initial,
                saveAsPrivate = initial.isPrivate,
                allowPrivate = allowPrivate,
            )
        }
    )
    val state: StateFlow<AddServerUiState> = _state

    /**
     * 切换服务端类型：协议回到该类型的首选值，端口按新类型默认值适配，
     * SMB 专用的域 / 共享目录在进出 SMB 时清空（`spec`：切换类型要重置相关字段）。
     */
    fun onSelectKind(next: ServerKind) {
        val s = _state.value
        if (s.kind == next) return
        // 端口：跨 SMB 边界一定换默认值；同族内只在「默认值没被手改」时才跟着换，
        // 避免把用户填好的端口覆盖掉（例如自建 Jellyfin 用了 8097）。
        val portTouched = s.port.isNotBlank() && s.port != s.kind.defaultPort.toString()
        val keepPort = !s.kind.isSmb && !next.isSmb && portTouched
        _state.value = s.copy(
            kind = next,
            protocol = next.protocols.first(),
            port = if (keepPort) s.port else next.defaultPort.toString(),
            path = if (next.isSmb) null else s.path,
            domain = "",
            share = "",
            authMode = if (next.authModes.isEmpty()) {
                AuthMode.PASSWORD
            } else {
                s.authMode.takeIf { it in next.authModes } ?: next.authModes.first()
            },
            probeStatus = ProbeStatus.Idle,
            submitStatus = SubmitStatus.Idle,
            error = null,
        )
    }

    /** 切换协议：SMB 行锁定，点了也不动。 */
    fun onSelectProtocol(next: NetProtocol) {
        val s = _state.value
        if (s.kind.protocolLocked || s.protocol == next) return
        _state.value = s.copy(protocol = next, probeStatus = ProbeStatus.Idle, error = null)
    }

    /**
     * 地址输入：容忍两种「粘贴」——
     * ① 带 scheme（`http://192.168.1.5`）→ 剥掉并把协议下拉拉过去；
     * ② 带端口（`192.168.1.5:8097`）→ 拆成地址 + 端口两行，不让用户手工再拆一遍。
     */
    fun onAddressChange(raw: String) {
        val s = _state.value
        val pastedProtocol = resolvePastedProtocol(raw)
        val protocol = when {
            pastedProtocol == null -> s.protocol
            s.kind.isSmb -> NetProtocol.SMB
            pastedProtocol == NetProtocol.SMB -> s.protocol
            pastedProtocol in s.kind.protocols -> pastedProtocol
            else -> s.protocol
        }
        val withoutScheme = if (pastedProtocol != null) raw.trim().substringAfter("://") else raw

        // 只在「一次跳了多个字符」（= 粘贴）时才拆端口：手敲 `192.168.1.5:80` 时
        // 逐字符拆分会把刚敲的冒号抢走，光标乱跳（真机试过）。
        val isPaste = raw.length - s.address.length > 1
        val split = if (isPaste) splitInlinePort(withoutScheme) else null
        _state.value = s.copy(
            address = split?.first ?: withoutScheme,
            port = split?.second ?: s.port,
            protocol = protocol,
            probeStatus = ProbeStatus.Idle,
            error = null,
        )
    }

    fun onPortChange(v: String) {
        _state.value = _state.value.copy(
            port = v.filter { it.isDigit() }.take(5),
            probeStatus = ProbeStatus.Idle,
            error = null,
        )
    }

    fun onDomainChange(v: String) {
        _state.value = _state.value.copy(domain = v, probeStatus = ProbeStatus.Idle, error = null)
    }

    /** 共享目录：统一存成不带前导斜杠的形式，拼 URL 时再补。 */
    fun onShareChange(v: String) {
        _state.value = _state.value.copy(
            share = v.trimStart('/'),
            probeStatus = ProbeStatus.Idle,
            error = null,
        )
    }

    fun onAuthModeChange(mode: AuthMode) {
        val s = _state.value
        if (s.authMode == mode) return
        _state.value = s.copy(authMode = mode, probeStatus = ProbeStatus.Idle, error = null)
    }

    fun onNameChange(v: String) {
        _state.value = _state.value.copy(name = v, error = null)
    }

    /** 「存为隐私源」开关：只在隐私模式下可见可用。 */
    fun onSaveAsPrivateChange(v: Boolean) {
        if (!_state.value.allowPrivate) return
        _state.value = _state.value.copy(saveAsPrivate = v, error = null)
    }

    fun onUsernameChange(v: String) {
        _state.value = _state.value.copy(username = v, error = null)
    }

    fun onPasswordChange(v: String) {
        _state.value = _state.value.copy(password = v, error = null)
    }

    fun onApiKeyChange(v: String) {
        _state.value = _state.value.copy(apiKey = v, error = null)
    }

    /** 测试连接：地址 → 各源自己的探活方式（SMB 走 445 TCP 连通性）。 */
    suspend fun onTest() {
        val s = _state.value
        if (s.address.isBlank()) {
            _state.value = s.copy(error = "请填写服务器地址")
            return
        }
        _state.value = s.copy(probeStatus = ProbeStatus.Testing, error = null)
        val result = probe(s.kind, s.targetUrl)
        _state.value = _state.value.copy(probeStatus = result, error = probeMessage(result))
    }

    /**
     * 保存 / 登录。
     *
     * 三条路径：
     * 1. 编辑且端点没动 → 只落库名称，返回上一页（凭据与缓存全部保留）
     * 2. 源模块未接入（SMB）→ 不动，按钮本来就置灰
     * 3. 其余 → 走 [ServerAuthGateway]：签发令牌 → 落盘配置 → 新增时设为当前服务器
     */
    suspend fun onSubmit() {
        val s = _state.value
        if (!s.kind.isPlayable) return
        if (s.isSubmitting) return

        if (s.isEditing && !s.endpointChanged) {
            if (s.name.isBlank()) {
                _state.value = s.copy(error = "请填写服务器名称")
                return
            }
            _state.value = s.copy(submitStatus = SubmitStatus.Running, error = null)
            saveLocal(s.name.trim())
            _state.value = _state.value.copy(submitStatus = SubmitStatus.Done)
            return
        }

        if (!s.canSubmit) {
            _state.value = s.copy(error = s.submitBlockReason)
            return
        }

        val sourceType = s.kind.sourceType ?: return
        _state.value = s.copy(submitStatus = SubmitStatus.Running, error = null)
        val result = auth.signIn(
            SignInRequest(
                serverId = s.original?.id ?: serverId,
                sourceType = sourceType,
                baseUrl = s.targetUrl,
                displayName = s.name.trim().ifBlank { null },
                authMode = s.authMode,
                username = s.username.trim(),
                // 无密码模式不能把上一次残留的密码带上去
                password = if (s.authMode == AuthMode.PASSWORD) s.password else "",
                apiKey = s.apiKey.trim(),
                domain = s.domain.trim(),
                activate = !s.isEditing,
                // 编辑已有服务器时不在本页切换「隐私归属」——存量源的挪动要走设置页的「移入隐私」，
                // 那里才会把令牌一起从普通区迁进加密区（决策 22）。
                isPrivate = s.saveAsPrivate && s.allowPrivate && !s.isEditing,
            )
        )
        _state.value = when (result) {
            SignInResult.Ok -> _state.value.copy(submitStatus = SubmitStatus.Done)
            is SignInResult.Failed -> _state.value.copy(submitStatus = SubmitStatus.Idle, error = result.message)
        }
    }
}

/** 探测结果 → 页面提示文案（`null` = 不需要额外提示）。 */
internal fun probeMessage(status: ProbeStatus): String? = when (status) {
    is ProbeStatus.NotFound -> "地址可达但没识别出该服务 —— 请检查端口与路径前缀。"
    is ProbeStatus.Unreachable -> "地址不可达：${status.reason}"
    else -> null
}

/**
 * 把 `host:port` 拆成两段（`192.168.1.5:8097` → `192.168.1.5` + `8097`）。
 *
 * 只认「最后一段全是数字」的形式；`http://` 已经在外面剥掉了。
 * 不是 `host:port` 形态时返回 null。
 */
internal fun splitInlinePort(input: String): Pair<String, String>? {
    val text = input.trim()
    if (!text.contains(':')) return null
    val host = text.substringBeforeLast(':')
    val port = text.substringAfterLast(':')
    if (host.isEmpty() || port.isEmpty() || !port.all(Char::isDigit)) return null
    // 带路径时不动（`nas:8096/emby` 这类交给路径字段，避免把路径吃进端口）
    if (port.contains('/')) return null
    return host to port.take(5)
}
