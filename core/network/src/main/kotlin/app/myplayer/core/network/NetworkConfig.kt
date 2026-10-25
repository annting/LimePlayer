package app.myplayer.core.network

import okhttp3.Interceptor

data class NetworkConfig(
    /** 每次请求前调用，返回要附加的鉴权头（如 `X-Emby-Token`）；空 map 表示不附加。 */
    val tokenProvider: () -> Map<String, String> = { emptyMap() },
    val allowCleartextHttp: Boolean = false,
    /** 构建期开关：为 true 时整个 client 无条件信任任意证书（测试 / 特殊构建用）。 */
    val trustSelfSignedCert: Boolean = false,
    /**
     * 运行期开关：每次 TLS 握手都会调用它。设置页「信任自签名证书」走这条 ——
     * 用户改完立即生效，不需要杀进程重建 client（见 [applyDynamicTrust]）。
     */
    val trustSelfSignedProvider: (() -> Boolean)? = null,
    val connectTimeoutSeconds: Long = 15,
    val readTimeoutSeconds: Long = 30,
    val extraInterceptors: List<Interceptor> = emptyList(),
)
