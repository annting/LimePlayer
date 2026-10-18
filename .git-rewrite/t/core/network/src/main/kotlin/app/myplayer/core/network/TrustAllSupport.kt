package app.myplayer.core.network

import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 仅在用户显式开启「信任自签证书」时调用（自建 NAS 常见自签证书场景）。
 * 默认关闭 —— 不要无条件放开 TLS 校验。
 */
internal fun OkHttpClient.Builder.applyTrustAll() {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), java.security.SecureRandom()) }
    sslSocketFactory(ctx.socketFactory, trustAll)
    hostnameVerifier { _, _ -> true }
}
