package app.myplayer.source.fnos

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class FnClientTest {

    private lateinit var server: MockWebServer
    private val secret = "TEST_SECRET_0123456789"
    private val apiKey = "TEST-KEY-0000"

    @Before fun setup() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun client() = FnClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        secret = secret,
        apiKey = apiKey,
        client = OkHttpClient(),
    )

    private fun md5(s: String) = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `登录解析 token 并带到后续请求`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"msg":"","data":{"token":"TK"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"msg":"","data":[{"guid":"g1","title":"跆拳道","category":"Movie"}]}"""))

        val c = client()
        assertThat(c.login("u", "p")).isEqualTo("TK")
        val first = server.takeRequest()
        assertThat(first.path).isEqualTo("/v/api/v1/login")
        assertThat(first.method).isEqualTo("POST")
        assertThat(first.getHeader("x-trim-client")).isEqualTo("web")
        assertThat(first.getHeader("authx")).contains("nonce=")

        val libs = c.get("/v/api/v1/mediadb/list").arr("data")!!
        assertThat(libs).hasSize(1)
        val second = server.takeRequest()
        assertThat(second.getHeader("Authorization")).isEqualTo("TK")
    }

    @Test
    fun `GET 签名与公式一致`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":null}"""))
        client().get("/v/api/v1/mediadb/list")
        val req = server.takeRequest()
        val authx = req.getHeader("authx")!!
        // nonce=NNN&timestamp=TTT&sign=SSS
        val parts = authx.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
        val expected = md5(
            listOf(secret, "/v/api/v1/mediadb/list", parts["nonce"], parts["timestamp"], md5(""), apiKey)
                .joinToString("_")
        )
        assertThat(parts["sign"]).isEqualTo(expected)
    }

    @Test
    fun `POST 签名使用请求体哈希`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":null}"""))
        val body = buildJsonObject { put("item_guid", "abc") }
        client().post("/v/api/v1/play/info", body)
        val req = server.takeRequest()
        val raw = req.body.readUtf8()
        val parts = req.getHeader("authx")!!.split("&").associate {
            it.substringBefore("=") to it.substringAfter("=")
        }
        val expected = md5(
            listOf(secret, "/v/api/v1/play/info", parts["nonce"], parts["timestamp"], md5(raw), apiKey)
                .joinToString("_")
        )
        assertThat(parts["sign"]).isEqualTo(expected)
        assertThat(raw).contains("\"item_guid\"")
    }

    @Test
    fun `业务错误码抛出异常`() {
        server.enqueue(MockResponse().setBody("""{"code":5000,"msg":"invalid sign","data":null}"""))
        val e = runCatching { runBlocking { client().get("/v/api/v1/mediadb/list") } }.exceptionOrNull()
        assertThat(e).isInstanceOf(FnException::class.java)
        assertThat(e!!.message).contains("5000")
    }
}
