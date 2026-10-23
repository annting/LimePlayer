package app.myplayer.feature.server

import app.myplayer.core.model.SourceType

/**
 * 「添加 / 修改服务器」页的能力模型。
 *
 * 页面是按**服务端类型**分叉的（`docs/03-UI设计规范.md` §9）：
 * 不同类型的默认端口、可选协议、认证方式、是否要共享目录都不一样，
 * 所以把这些差异收进 [ServerKind]，页面只读属性、不写 `if (type == ...)`。
 *
 * ⚠️ [ServerKind.SMB] 目前**没有对应的源模块**（`sourceType == null`）：
 * 页面上可以完整填写、可以做 445 端口探活，但「保存 / 登录」不开。
 */

/** 协议行上的取值。SMB 不是 HTTP，但它占的就是「协议」这一行的位置。 */
enum class NetProtocol(val label: String) {
    HTTP("HTTP"),
    HTTPS("HTTPS"),
    SMB("SMB"),
    ;

    /** 拼 URL 用的小写 scheme。 */
    val scheme: String get() = label.lowercase()
}

/** 身份验证方式（仅 HTTP 类服务端有；SMB 固定「账号 + 密码」）。 */
enum class AuthMode(val label: String) {
    PASSWORD("账号密码"),
    NO_PASSWORD("无密码"),
    API_KEY("API Key"),
}

/**
 * 服务端类型。
 *
 * @param sourceType 落库用的 [SourceType]；`null` = 源模块尚未接入（只做页面）
 * @param defaultPort 切换类型时的端口回落值
 * @param protocols 该类型支持的协议（只有一个时协议行锁定）
 * @param authModes 该类型支持的身份验证方式（空 = 不显示「认证方式」行）
 */
enum class ServerKind(
    val label: String,
    val description: String,
    val sourceType: SourceType?,
    val defaultPort: Int,
    val protocols: List<NetProtocol>,
    val authModes: List<AuthMode>,
) {
    EMBY(
        label = "Emby",
        description = "Emby 媒体服务器 · 默认 8096",
        sourceType = SourceType.EMBY,
        defaultPort = 8096,
        protocols = listOf(NetProtocol.HTTP, NetProtocol.HTTPS),
        authModes = listOf(AuthMode.PASSWORD, AuthMode.NO_PASSWORD, AuthMode.API_KEY),
    ),
    JELLYFIN(
        label = "Jellyfin",
        description = "Jellyfin 媒体服务器 · 默认 8096",
        sourceType = SourceType.JELLYFIN,
        defaultPort = 8096,
        protocols = listOf(NetProtocol.HTTP, NetProtocol.HTTPS),
        authModes = listOf(AuthMode.PASSWORD, AuthMode.NO_PASSWORD, AuthMode.API_KEY),
    ),
    FNOS(
        label = "飞牛影视",
        description = "fnOS 影视中心 · 默认 5666",
        sourceType = SourceType.FNOS,
        defaultPort = 5666,
        protocols = listOf(NetProtocol.HTTP, NetProtocol.HTTPS),
        authModes = listOf(AuthMode.PASSWORD, AuthMode.NO_PASSWORD, AuthMode.API_KEY),
    ),
    SMB(
        label = "SMB",
        description = "Windows / NAS 共享目录 · 默认 445",
        sourceType = null,
        defaultPort = 445,
        protocols = listOf(NetProtocol.SMB),
        authModes = emptyList(),
    ),
    ;

    /** SMB 分支：多出「域 / 共享目录」两行，且没有「认证方式」下拉。 */
    val isSmb: Boolean get() = this == SMB

    /** 协议行是否锁定（SMB 固定 SMB，不可点）。 */
    val protocolLocked: Boolean get() = protocols.size <= 1

    /** 是否有「认证方式」下拉（SMB 没有，直接给账号密码）。 */
    val hasAuthModePicker: Boolean get() = authModes.isNotEmpty()

    /** 源模块是否已接入：未接入的类型「保存 / 登录」置灰。 */
    val isPlayable: Boolean get() = sourceType != null

    companion object {
        /** 反查：已落库的 [SourceType] → 页面上的类型。 */
        fun of(type: SourceType): ServerKind = entries.firstOrNull { it.sourceType == type } ?: EMBY
    }
}

/** 网络与路径分组里的行。 */
enum class NetworkField { Address, Port, Domain, Share }

/** 身份验证分组里的行。 */
enum class AuthField { Mode, Username, Password, ApiKey }

/**
 * 「网络与路径」分组的行序 —— 纯函数，便于单测，页面靠它决定每行是否画分隔线。
 */
fun networkFieldOrder(kind: ServerKind): List<NetworkField> =
    if (kind.isSmb) {
        listOf(NetworkField.Address, NetworkField.Port, NetworkField.Domain, NetworkField.Share)
    } else {
        listOf(NetworkField.Address, NetworkField.Port)
    }

/**
 * 「身份验证」分组的行序（随「认证方式」动态变化）。
 *
 * - SMB：账号 + 密码（无「认证方式」行）
 * - 账号密码：认证方式 + 账号 + 密码
 * - 无密码：认证方式 + 账号
 * - API Key：认证方式 + API Key
 */
fun authFieldOrder(kind: ServerKind, mode: AuthMode): List<AuthField> {
    if (kind.isSmb) return listOf(AuthField.Username, AuthField.Password)
    return when (mode) {
        AuthMode.PASSWORD -> listOf(AuthField.Mode, AuthField.Username, AuthField.Password)
        AuthMode.NO_PASSWORD -> listOf(AuthField.Mode, AuthField.Username)
        AuthMode.API_KEY -> listOf(AuthField.Mode, AuthField.ApiKey)
    }
}

/** 把落库的 `baseUrl` 拆成「协议 + 主机 + 端口 + 路径前缀」四段。 */
data class ParsedServerUrl(
    val protocol: NetProtocol?,
    val host: String,
    val port: String?,
    /** 以 `/` 开头的路径前缀（如 `/emby`）；没有则为 null。 */
    val path: String?,
)

/**
 * 拆分 `baseUrl` —— 编辑模式预填用，必须**原样往返**（[buildServerUrl] 拼回来要和原值一致）：
 *
 * - `http://192.168.31.15:8096` → HTTP / `192.168.31.15` / `8096` / null
 * - `https://nas:8920`          → HTTPS / `nas` / `8920` / null
 * - `http://nas:8096/emby`      → HTTP / `nas` / `8096` / `/emby`
 * - `smb://nas:445/media`       → SMB / `nas` / `445` / `/media`
 * - `nas`（没有 scheme）        → null / `nas` / null / null
 */
fun parseServerUrl(baseUrl: String): ParsedServerUrl {
    val text = baseUrl.trim()
    if (text.isEmpty()) return ParsedServerUrl(null, "", null, null)
    val protocol = when {
        text.startsWith("https://", ignoreCase = true) -> NetProtocol.HTTPS
        text.startsWith("http://", ignoreCase = true) -> NetProtocol.HTTP
        text.startsWith("smb://", ignoreCase = true) -> NetProtocol.SMB
        else -> null
    }
    val rest = if (protocol != null) text.substringAfter("://") else text
    val authority = rest.substringBefore("/")
    val rawPath = rest.substringAfter("/", "").trim('/')
    val path = rawPath.takeIf { it.isNotEmpty() }?.let { "/$it" }
    val maybePort = if (authority.contains(':')) authority.substringAfterLast(':') else null
    val port = maybePort?.takeIf { p -> p.isNotEmpty() && p.all(Char::isDigit) }
    val host = if (port != null) authority.substringBeforeLast(':') else authority
    return ParsedServerUrl(protocol, host, port, path)
}

/**
 * 拼回 URL（探测、保存、展示都用它，避免三处算法漂移）。
 *
 * 容错：用户可能把 `http://` 一起粘进地址框 —— scheme 以协议下拉为准，
 * 粘进来的那一段会被剥掉（纠偏在 [resolvePastedProtocol] 里做）。
 *
 * 端口：地址里**自带**端口时以它为准（用户明明白白敲进去的），否则用端口字段；
 * `http:80` / `https:443` 这类默认端口不写进 URL。
 */
fun buildServerUrl(
    protocol: NetProtocol,
    host: String,
    port: String?,
    path: String? = null,
): String {
    val raw = host.trim().trimEnd('/')
    if (raw.isEmpty()) return ""
    val withoutScheme = raw.substringAfter("://", raw)
    val authority = withoutScheme.substringBefore('/')
    val inlinePort = authority.substringAfterLast(':', "")
        .takeIf { authority.contains(':') && it.isNotEmpty() && it.all(Char::isDigit) }
    val hostOnly = if (inlinePort != null) authority.substringBeforeLast(':') else authority
    val inlinePath = withoutScheme.substringAfter('/', "").trim('/')

    val portNumber = inlinePort?.toIntOrNull() ?: port?.trim()?.toIntOrNull()
    val omitPort = portNumber == null ||
        (protocol == NetProtocol.HTTP && portNumber == 80) ||
        (protocol == NetProtocol.HTTPS && portNumber == 443)

    val builder = StringBuilder()
    builder.append(protocol.scheme).append("://").append(hostOnly)
    if (!omitPort) builder.append(':').append(portNumber)
    listOf(inlinePath, path?.trim()?.trim('/').orEmpty())
        .filter { it.isNotEmpty() }
        .forEach { builder.append('/').append(it) }
    return builder.toString()
}

/**
 * 用户把 scheme 粘进地址框时，把协议下拉「拉」到一致的方向 —— 返回 null 表示没粘 scheme。
 *
 * 只认 http/https/smb，其它（如误粘 `ftp://`）当普通文本，不纠偏。
 */
fun resolvePastedProtocol(input: String): NetProtocol? {
    val text = input.trim()
    return when {
        text.startsWith("https://", ignoreCase = true) -> NetProtocol.HTTPS
        text.startsWith("http://", ignoreCase = true) -> NetProtocol.HTTP
        text.startsWith("smb://", ignoreCase = true) -> NetProtocol.SMB
        else -> null
    }
}

// ---- 登录通道（新增页内直接登录，不再跳二次登录页） ----

/** 一次登录请求：页面把「填了什么」整体交给网关，网关负责落库。 */
data class SignInRequest(
    val serverId: String,
    val sourceType: SourceType,
    val baseUrl: String,
    /** 用户自定义的服务器名（修改页填的），登录成功时优先于服务端账号名。 */
    val displayName: String?,
    val authMode: AuthMode,
    val username: String,
    val password: String,
    val apiKey: String,
    /** 登录成功后是否把它设为当前服务器（新增 = true，重新登录 = false）。 */
    val activate: Boolean,
)

sealed interface SignInResult {
    data object Ok : SignInResult
    data class Failed(val message: String) : SignInResult
}

/**
 * 登录 + 落盘（令牌 / 配置 / 当前服务器）的唯一入口。
 *
 * 生产实现在 `:app`（它同时知道 OkHttp、各源的登录端点与三个 Store）；测试传假实现。
 */
fun interface ServerAuthGateway {
    suspend fun signIn(request: SignInRequest): SignInResult
}
