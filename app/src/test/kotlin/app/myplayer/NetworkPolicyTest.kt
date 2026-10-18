package app.myplayer

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 明文 HTTP 运行时开关（任务 5-4）：关 → http 请求被拦截并给出指引；
 * 开 → 放行；https 不受开关影响。
 */
class NetworkPolicyTest {

    private lateinit var server: MockWebServer
    private val policy = NetworkPolicy()

    @Before fun setup() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun client() = OkHttpClient.Builder()
        .addInterceptor(cleartextGuard(policy))
        .build()

    private fun get(url: String): Response =
        client().newCall(Request.Builder().url(url).build()).execute()

    @Test fun `开关关闭时 http 请求被拦截并提示设置页`() {
        policy.allowCleartextHttp = false
        server.enqueue(MockResponse().setBody("should not reach"))
        val e = runCatching { get(server.url("/x").toString()) }.exceptionOrNull()

        assertThat(e).isInstanceOf(IOException::class.java)
        assertThat(e!!.message).contains("明文 HTTP 已关闭")
        assertThat(server.requestCount).isEqualTo(0)   // 请求没发出去
    }

    @Test fun `开关打开时 http 放行`() {
        policy.allowCleartextHttp = true
        server.enqueue(MockResponse().setBody("ok"))
        get(server.url("/x").toString()).use { assertThat(it.code).isEqualTo(200) }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `https 不受开关影响`() {
        policy.allowCleartextHttp = false
        // https 请求会因 MockWebServer 无证书而在 TLS 层失败，但绝不能是「明文已关闭」
        val e = runCatching { get(server.url("/x").toString().replace("http://", "https://")) }
            .exceptionOrNull()
        assertThat(e!!.message).doesNotContain("明文 HTTP 已关闭")
    }
}
