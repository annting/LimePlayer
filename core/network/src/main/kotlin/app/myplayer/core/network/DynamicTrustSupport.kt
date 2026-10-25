package app.myplayer.core.network

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 「信任自签证书」的**运行时**版本（2026-10-25）。
 *
 * 旧实现（[applyTrustAll]）只能二选一地在**构建 OkHttpClient 时**决定 —— 而 client 是
 * `@Singleton`、App 启动即构建，用户在设置页打开开关后必须杀进程才生效，
 * 结果就是「开关形同虚设」。
 *
 * 现在把判断挪到 **TLS 握手时**：开关打开 → 放行任意证书与主机名；
 * 关闭 → 原样交回平台默认校验（该报错还是报错）。
 */
internal fun OkHttpClient.Builder.applyDynamicTrust(trustSelfSigned: () -> Boolean) {
    val delegate = platformTrustManager()
    val manager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (!trustSelfSigned()) delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (trustSelfSigned()) return
            delegate.checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            if (trustSelfSigned()) emptyArray() else delegate.acceptedIssuers
    }
    val ctx = SSLContext.getInstance("TLS").apply {
        init(null as Array<KeyManager>?, arrayOf<X509TrustManager>(manager), SecureRandom())
    }
    sslSocketFactory(ctx.socketFactory, manager)
    // 主机名校验同理：默认走平台实现，开关打开才跳过（自签证书的 CN 通常对不上内网 IP）
    hostnameVerifier { hostname, session ->
        trustSelfSigned() || HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
    }
}

/** 平台默认的 X509TrustManager（系统信任库）。 */
private fun platformTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        ?: error("系统没有可用的 X509TrustManager")
}
