package app.myplayer.feature.server

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 飞牛影视探测：`GET /v/api/v1/sys/version` 是唯一免签名端点（`docs/05` §6.9）。
 */
class ProbeFnosTest {

    private lateinit var server: MockWebServer

    @Before fun setup() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun base() = server.url("/").toString().trimEnd('/')

    @Test fun `返回版本号即识别为飞牛影视`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"msg":"","code":0,"data":{"version":"0.9.8","mediasrvVersion":"0.8.41"}}""")
        )
        val status = probeFnos(OkHttpClient(), base())
        assertThat(status).isInstanceOf(ProbeStatus.Reachable::class.java)
        val ok = status as ProbeStatus.Reachable
        assertThat(ok.serverName).isEqualTo("飞牛影视")
        assertThat(ok.productName).isEqualTo("fnOS Media")
        assertThat(ok.version).contains("0.9.8")
        assertThat(ok.version).contains("0.8.41")
        // 探测必须打在免签名端点上
        assertThat(server.takeRequest().path).isEqualTo("/v/api/v1/sys/version")
    }

    @Test fun `非飞牛服务返回不可用而非崩`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":5000,"msg":"invalid sign","data":null}"""))
        val status = probeFnos(OkHttpClient(), base())
        assertThat(status).isInstanceOf(ProbeStatus.Unreachable::class.java)
        assertThat((status as ProbeStatus.Unreachable).reason).contains("不是飞牛影视")
    }

    @Test fun `404 视为路径或端口不对`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(probeFnos(OkHttpClient(), base())).isEqualTo(ProbeStatus.NotFound)
    }
}
