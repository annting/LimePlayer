package app.myplayer.core.network

import okhttp3.Interceptor

data class NetworkConfig(
    /** 每次请求前调用，返回要附加的鉴权头（如 `X-Emby-Token`）；空 map 表示不附加。 */
    val tokenProvider: () -> Map<String, String> = { emptyMap() },
    val allowCleartextHttp: Boolean = false,
    val trustSelfSignedCert: Boolean = false,
    val connectTimeoutSeconds: Long = 15,
    val readTimeoutSeconds: Long = 30,
    val extraInterceptors: List<Interceptor> = emptyList(),
)
