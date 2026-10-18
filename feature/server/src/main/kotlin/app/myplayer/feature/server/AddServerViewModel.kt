package app.myplayer.feature.server

import app.myplayer.core.model.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 探测结果与探测状态合为一个类型，避免 ProbeResult / ProbeStatus 两份模型来回映射。 */
sealed interface ProbeStatus {
    data object Idle : ProbeStatus
    data object Testing : ProbeStatus
    data class Reachable(val serverName: String, val productName: String?, val version: String?) : ProbeStatus
    data object NotFound : ProbeStatus
    data class Unreachable(val reason: String) : ProbeStatus
}

/** 源的展示名（登录页标题、服务器卡片都用它，避免各页面各写一套）。 */
fun sourceDisplayName(type: SourceType): String = when (type) {
    SourceType.EMBY -> "Emby"
    SourceType.JELLYFIN -> "Jellyfin"
    SourceType.FNOS -> "飞牛影视"
    SourceType.LOCAL -> "本地文件"
}

/** 各源默认端口：飞牛影视 Web 端 5666（实测），Emby/Jellyfin 8096。 */
fun defaultPort(type: SourceType): Int = when (type) {
    SourceType.FNOS -> 5666
    else -> 8096
}

data class AddServerUiState(
    val sourceType: SourceType = SourceType.EMBY,
    val address: String = "",
    val port: String = "8096",
    val probeStatus: ProbeStatus = ProbeStatus.Idle,
    val error: String? = null,
) {
    val canProceed: Boolean get() = probeStatus is ProbeStatus.Reachable
    val sourceName: String get() = sourceDisplayName(sourceType)
    val portHint: String get() = defaultPort(sourceType).toString()
}

class AddServerViewModel(
    /** 生产环境按类型分派到 `probeServer` / `probeFnos`；测试传假实现。 */
    private val probe: suspend (SourceType, String) -> ProbeStatus,
    initialSource: SourceType = SourceType.EMBY,
) {
    private val _state = MutableStateFlow(
        AddServerUiState(sourceType = initialSource, port = defaultPort(initialSource).toString())
    )
    val state: StateFlow<AddServerUiState> = _state

    /**
     * 切换源：端口只在该源**默认值未被手改**时才跟着换，
     * 避免把用户填好的端口覆盖掉（例如自建 Jellyfin 用了 8097）。
     */
    fun onSelectSource(type: SourceType) {
        val s = _state.value
        if (s.sourceType == type) return
        val portTouched = s.port.isNotBlank() && s.port != defaultPort(s.sourceType).toString()
        _state.value = s.copy(
            sourceType = type,
            port = if (portTouched) s.port else defaultPort(type).toString(),
            probeStatus = ProbeStatus.Idle,
            error = null,
        )
    }

    fun onAddressChange(v: String) {
        _state.value = _state.value.copy(address = v, probeStatus = ProbeStatus.Idle, error = null)
    }

    fun onPortChange(v: String) {
        _state.value = _state.value.copy(port = v, probeStatus = ProbeStatus.Idle, error = null)
    }

    suspend fun onTest() {
        val s = _state.value
        val base = normalizeBaseUrl(s.address, s.port.toIntOrNull() ?: defaultPort(s.sourceType))
        if (base.isEmpty()) {
            _state.value = s.copy(error = "请填写服务器地址")
            return
        }
        _state.value = s.copy(probeStatus = ProbeStatus.Testing, error = null)
        when (val result = probe(s.sourceType, base)) {
            is ProbeStatus.Reachable -> _state.value = s.copy(probeStatus = result)
            ProbeStatus.NotFound -> _state.value = s.copy(
                probeStatus = result,
                error = "服务器响应 404：端口或路径前缀可能不对，请检查端口后重试",
            )
            is ProbeStatus.Unreachable -> _state.value = s.copy(
                probeStatus = result,
                error = "地址不可达：${result.reason}",
            )
            else -> _state.value = s.copy(probeStatus = result)
        }
    }
}

/** 输入框智能补全 `http://`；已写协议/端口则不重复追加，默认端口（80/443）也不追加。 */
fun normalizeBaseUrl(hostInput: String, port: Int): String {
    val raw = hostInput.trim().trimEnd('/')
    if (raw.isEmpty()) return ""
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val scheme = withScheme.substringBefore("://")
    val afterScheme = withScheme.substringAfter("://")
    val hasPort = afterScheme.contains(":")
    val defaultForScheme = if (scheme == "https") 443 else 80
    return if (hasPort || port == defaultForScheme) withScheme else "$withScheme:$port"
}
