package app.myplayer.source.jellyfin

import app.myplayer.core.model.SourceType
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class JellyfinSourceTest {

    private lateinit var server: MockWebServer

    @Before fun setup() { server = MockWebServer().apply { start() } }

    @After fun tearDown() = server.shutdown()

    private fun source(prefix: String = "") = JellyfinSource.create(
        client = OkHttpClient(),
        baseUrl = server.url("/").toString().trimEnd('/'),
        serverId = "jf1",
        token = "TOKEN",
        userId = "u1",
        deviceId = "dev1",
        prefix = prefix,
    )

    @Test
    fun `类型为 JELLYFIN`() {
        assertThat(source().type).isEqualTo(SourceType.JELLYFIN)
        assertThat(source().serverId).isEqualTo("jf1")
    }

    @Test
    fun `鉴权头使用 MediaBrowser Authorization 形态`() {
        server.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""))
        kotlinx.coroutines.runBlocking { source().getLibraries() }
        val req = server.takeRequest()
        val auth = req.getHeader("Authorization")
        assertThat(auth).isNotNull()
        assertThat(auth).contains("MediaBrowser")
        assertThat(auth).contains("Token=\"TOKEN\"")
        assertThat(req.getHeader("X-Emby-Token")).isNull()
    }

    @Test
    fun `路径前缀可覆盖`() {
        server.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""))
        kotlinx.coroutines.runBlocking { source(prefix = "/jellyfin").getLibraries() }
        assertThat(server.takeRequest().path).startsWith("/jellyfin/Users/")
    }
}
