package app.myplayer.source.emby

import app.myplayer.core.model.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class EmbySourceTest {
    private lateinit var server: MockWebServer
    private lateinit var source: EmbySource

    private val caps = ClientCapabilities(
        supportedVideoCodecs = setOf("hevc", "h264"), supportedAudioCodecs = setOf("aac", "ac3"),
        maxWidth = 3840, maxHeight = 2160, supportsHdr = true, deviceName = "test", deviceId = "d1",
    )

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        source = EmbySource.create(
            client = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
            serverId = "srv-emby",
            token = "T",
            userId = "u1",
            deviceId = "d1",
        )
    }
    @After fun teardown() { server.shutdown() }

    @Test fun `getItems returns mapped items`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"i1","Name":"Dune","RunTimeTicks":60000000,"ImageTags":{"Primary":"t"},"MediaSources":[{"Id":"ms1","Size":123}]}],"TotalRecordCount":1}"""))
        val page = source.getItems(ItemQuery(libraryId = "lib1"))
        assertThat(page.items.first().id).isEqualTo("i1")
        assertThat(page.items.first().sizeBytes).isEqualTo(123L)
        assertThat(page.items.first().sourceType).isEqualTo(SourceType.EMBY)
        assertThat(page.items.first().hasPoster).isTrue()
    }

    @Test fun `planPlayback prefers direct play`() = runTest {
        server.enqueue(MockResponse().setBody("""{"MediaSources":[{"Id":"ms1","SupportsDirectPlay":true}]}"""))
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune")
        val plan = source.planPlayback(item, caps)
        assertThat(plan).isInstanceOf(PlaybackPlan.Direct::class.java)
        assertThat((plan as PlaybackPlan.Direct).mediaSourceId).isEqualTo("ms1")
    }

    /** 服务端给的 TranscodingUrl 是相对路径，最终必须变成能被 ExoPlayer 直接使用的绝对 URL。 */
    @Test fun `planPlayback 拼接相对转码地址为绝对 URL`() = runTest {
        server.enqueue(MockResponse().setBody("""{"MediaSources":[{"Id":"ms1","SupportsDirectPlay":false,"SupportsDirectStream":false,"SupportsTranscoding":true,"TranscodingUrl":"/videos/i1/master.m3u8"}]}"""))
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune")
        val plan = source.planPlayback(item, caps) as PlaybackPlan.Transcode
        assertThat(plan.hlsUrl).isEqualTo("${server.url("/").toString().trimEnd('/')}/videos/i1/master.m3u8")
        assertThat(plan.isRemuxOnly).isFalse()
    }

    @Test fun `planPlayback 标记纯重封装`() = runTest {
        server.enqueue(MockResponse().setBody("""{"MediaSources":[{"Id":"ms1","SupportsDirectPlay":false,"SupportsDirectStream":true,"SupportsTranscoding":true,"TranscodingUrl":"/videos/i1/master.m3u8?a=1"}]}"""))
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune")
        val plan = source.planPlayback(item, caps) as PlaybackPlan.Transcode
        assertThat(plan.isRemuxOnly).isTrue()
    }

    /** 支持转码但没回 TranscodingUrl：按固定入口自拼 HLS，而不是退回直连。 */
    @Test fun `planPlayback 缺转码地址时自拼 HLS 入口`() = runTest {
        server.enqueue(MockResponse().setBody("""{"MediaSources":[{"Id":"ms1","SupportsDirectPlay":false,"SupportsTranscoding":true}]}"""))
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune")
        val plan = source.planPlayback(item, caps) as PlaybackPlan.Transcode
        assertThat(plan.hlsUrl).contains("/Videos/i1/master.m3u8")
        assertThat(plan.isRemuxOnly).isFalse()
    }

    /** 令牌走请求头，不进 URL（否则会出现在 Coil 的磁盘缓存键与日志里）。 */
    @Test fun `image 返回带鉴权头的 remote url`() = runTest {
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune", hasPoster = true)
        val img = source.image(item, ImageKind.POSTER, ImageSize.MEDIUM) as ImageSource.Remote
        assertThat(img.url).contains("/Items/i1/Images/Primary")
        assertThat(img.url).doesNotContain("api_key")
        assertThat(img.headers["X-Emby-Token"]).isEqualTo("T")
    }

    @Test fun `无海报时返回 None 而不是 404 的 URL`() = runTest {
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune", hasPoster = false)
        assertThat(source.image(item, ImageKind.POSTER, ImageSize.MEDIUM)).isEqualTo(ImageSource.None)
    }
}
