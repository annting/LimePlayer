package app.myplayer.source.httpapi

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class TrickplayPlaylistTest {

    private val sample = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXT-X-TILES:RESOLUTION=320x180,LAYOUT=5x4,INTERVAL=10.000
        #EXTINF:10.000,
        0.jpg
        #EXTINF:10.000,
        1.jpg
        #EXTINF:10.000,
        2.jpg
    """.trimIndent()

    @Test
    fun `解析 RESOLUTION LAYOUT INTERVAL 与分片数`() {
        val p = TrickplayPlaylist.parse(sample)
        assertThat(p).isNotNull()
        assertThat(p!!.tileWidth).isEqualTo(320)
        assertThat(p.tileHeight).isEqualTo(180)
        assertThat(p.columns).isEqualTo(5)
        assertThat(p.rows).isEqualTo(4)
        assertThat(p.intervalMs).isEqualTo(10_000L)
        assertThat(p.segmentCount).isEqualTo(3)
    }

    @Test
    fun `缺少 EXT-X-TILES 时返回 null`() {
        assertThat(TrickplayPlaylist.parse("#EXTM3U\n0.jpg")).isNull()
    }

    @Test
    fun `非法数值时返回 null`() {
        val bad = "#EXTM3U\n#EXT-X-TILES:RESOLUTION=abc,LAYOUT=5x4,INTERVAL=10.000\n0.jpg"
        assertThat(TrickplayPlaylist.parse(bad)).isNull()
    }

    @Test
    fun `分片数为 0 时返回 null`() {
        val noSegments = "#EXTM3U\n#EXT-X-TILES:RESOLUTION=320x180,LAYOUT=5x4,INTERVAL=10.000\n"
        assertThat(TrickplayPlaylist.parse(noSegments)).isNull()
    }
}

/** trickplay 端点抓取（任务 3-1）：成功解析 / 404 降级。 */
class TrickplayApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HttpMediaApi

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        api = HttpMediaApi(
            client = OkHttpClient(),
            config = SourceConfig(
                serverId = "s1",
                baseUrl = server.url("/").toString().trimEnd('/'),
                token = "T",
                userId = "u1",
                deviceId = "d1",
                clientName = "myplayer",
            ),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `拉取分片清单走 Trickplay 端点并带鉴权头`() {
        server.enqueue(
            MockResponse().setBody(
                "#EXTM3U\n#EXT-X-TILES:RESOLUTION=320x180,LAYOUT=5x4,INTERVAL=10.000\n0.jpg\n"
            )
        )
        val text = kotlinx.coroutines.runBlocking { api.getTrickplayPlaylist("42", 320) }
        assertThat(text).contains("#EXT-X-TILES")

        val req = server.takeRequest()
        assertThat(req.path).contains("/Videos/42/Trickplay/320/tiles.m3u8")
        assertThat(req.getHeader("X-Emby-Token")).isEqualTo("T")
    }

    @Test
    fun `404 时抛异常由调用方降级`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val thrown = runCatching { kotlinx.coroutines.runBlocking { api.getTrickplayPlaylist("42", 320) } }
        assertThat(thrown.isFailure).isTrue()
    }

    @Test
    fun `分片图片 URL 规则正确`() {
        val tile = api.trickplayTileUrl("42", 320, 7)
        assertThat(tile).contains("/Videos/42/Trickplay/320/7.jpg")
    }
}
