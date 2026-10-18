package app.myplayer

import app.myplayer.core.datastore.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络策略的**运行时**持有者（任务 5-4）。
 *
 * `networkSecurityConfig` 是静态的：release 里放行明文（家庭 NAS 场景必须，否则 http:// 的
 * Emby / 飞牛在 release 构建直接连不上），「明文 HTTP」开关的**实际拦截**由 [cleartextGuard]
 * 在 OkHttp 层执行 —— 设置关闭时任何 `http://` 请求直接失败，错误信息指引回设置页。
 *
 * [bindSettings] 在 Application 启动时挂全局收集，设置改动即时生效。
 */
@Singleton
class NetworkPolicy @Inject constructor() {
    @Volatile var allowCleartextHttp: Boolean = false
}

/** 组装进 `NetworkConfig.extraInterceptors`：设置关闭时拦截所有明文请求。 */
fun cleartextGuard(policy: NetworkPolicy): Interceptor = Interceptor { chain ->
    val request = chain.request()
    if (!policy.allowCleartextHttp && request.url.scheme == "http") {
        throw IOException("明文 HTTP 已关闭：请在「设置 → 网络」中开启后再连接 ${request.url.host}")
    }
    chain.proceed(request)
}

fun NetworkPolicy.bindSettings(settingsStore: SettingsStore, scope: CoroutineScope) {
    scope.launch {
        settingsStore.settings.collect { allowCleartextHttp = it.allowCleartextHttp }
    }
}
