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

data class AddServerUiState(
    val address: String = "",
    val port: String = "",
    val probeStatus: ProbeStatus = ProbeStatus.Idle,
    val detectedSourceType: SourceType = SourceType.EMBY,
    val error: String? = null,
) {
    val canProceed: Boolean get() = probeStatus is ProbeStatus.Reachable
}

class AddServerViewModel(
    private val probe: suspend (baseUrl: String, port: String) -> ProbeStatus,
    private val defaultPort: Int = 8096,      // M1 只有 Emby；M4 增加飞牛卡片时按所选类型传入 8005
) {
    private val _state = MutableStateFlow(AddServerUiState(port = defaultPort.toString()))
    val state: StateFlow<AddServerUiState> = _state

    fun onAddressChange(v: String) {
        _state.value = _state.value.copy(address = v, probeStatus = ProbeStatus.Idle, error = null)
    }

    fun onPortChange(v: String) {
        _state.value = _state.value.copy(port = v, probeStatus = ProbeStatus.Idle, error = null)
    }

    suspend fun onTest() {
        val s = _state.value
        val base = normalizeBaseUrl(s.address, s.port.toIntOrNull() ?: defaultPort)
        if (base.isEmpty()) {
            _state.value = s.copy(error = "请填写服务器地址")
            return
        }
        _state.value = s.copy(probeStatus = ProbeStatus.Testing, error = null)
        when (val result = probe(base, s.port)) {
            is ProbeStatus.Reachable -> _state.value = s.copy(
                probeStatus = result,
                detectedSourceType = detectSourceType(result.serverName, result.productName),
            )
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

    /** 设备识别：ProductName 含 fnOS 或服务器名含「飞牛」→ 走 Jellyfin 协议。 */
    fun detectSourceType(serverName: String?, productName: String?): SourceType =
        if (productName?.contains("fnOS", ignoreCase = true) == true ||
            serverName?.contains("飞牛") == true
        ) SourceType.JELLYFIN else SourceType.EMBY
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
