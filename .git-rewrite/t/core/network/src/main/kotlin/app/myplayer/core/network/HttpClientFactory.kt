package app.myplayer.core.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientFactory {
    fun create(config: NetworkConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(AuthHeaderInterceptor(config.tokenProvider))
        if (config.trustSelfSignedCert) builder.applyTrustAll()
        config.extraInterceptors.forEach(builder::addInterceptor)
        return builder.build()
    }

    private class AuthHeaderInterceptor(private val provider: () -> Map<String, String>) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val headers = provider()
            if (headers.isEmpty()) return chain.proceed(chain.request())
            val request = chain.request().newBuilder().apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }.build()
            return chain.proceed(request)
        }
    }
}
