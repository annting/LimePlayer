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

    /** 详情页分季列表：带图与简介拉整部剧的集，并映射季号 / 集号。 */
    @Test fun `getEpisodes 映射分集并带图带简介`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"e1","Name":"第一集","ParentIndexNumber":1,"IndexNumber":1,"Overview":"开场","ImageTags":{"Primary":"t"}}],"TotalRecordCount":1}"""))
        val episodes = source.getEpisodes("s1")
        assertThat(episodes.single().id).isEqualTo("e1")
        assertThat(episodes.single().seasonNumber).isEqualTo(1)
        assertThat(episodes.single().episodeNumber).isEqualTo(1)
        assertThat(episodes.single().overview).isEqualTo("开场")
        assertThat(episodes.single().hasPoster).isTrue()
        val path = server.takeRequest().path!!
        assertThat(path).contains("EnableImages=true")
        assertThat(path).contains("Overview")
    }

    @Test fun `未登录时 getEpisodes 返回空列表`() = runTest {
        val anonymous = EmbySource.create(
            client = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
            serverId = "srv-emby", token = null, userId = null, deviceId = "d1",
        )
        assertThat(anonymous.getEpisodes("s1")).isEmpty()
    }

    // ---- 详情页写操作与人物页（`05` §2.2 / §5.1，详情页二期）----

    @Test fun `canMarkPlayed 为 true`() {
        assertThat(source.canMarkPlayed).isTrue()
    }

    /** markPlayed：POST 端点 + 服务端权威 UserData 原样映射。 */
    @Test fun `markPlayed 返回服务端权威 UserData`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Played":true,"PlaybackPositionTicks":0,"IsFavorite":false,"PlayCount":5}"""))
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune")
        val ud = source.markPlayed(item, played = true)
        assertThat(ud.isPlayed).isTrue()
        assertThat(ud.resumePositionTicks).isEqualTo(Ticks.ZERO)
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/Users/u1/PlayedItems/i1")
    }

    @Test fun `unmarkPlayed 走 DELETE 并返回未看状态`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Played":false,"PlaybackPositionTicks":12000000,"IsFavorite":true,"PlayCount":0}"""))
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune")
        val ud = source.markPlayed(item, played = false)
        assertThat(ud.isPlayed).isFalse()
        assertThat(ud.resumePositionTicks).isEqualTo(Ticks(12_000_000L))
        assertThat(ud.isFavorite).isTrue()
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
    }

    @Test fun `deleteItem 成功返回 true`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune")
        assertThat(source.deleteItem(item)).isTrue()
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("DELETE")
        assertThat(req.path).isEqualTo("/Items/i1")
    }

    @Test fun `getPersonWorks 返回该人物的作品`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"m1","Name":"沙丘","RunTimeTicks":9000000000}],"TotalRecordCount":1}"""))
        val works = source.getPersonWorks("p1")
        assertThat(works.single().id).isEqualTo("m1")
        assertThat(works.single().title).isEqualTo("沙丘")
        assertThat(server.takeRequest().path).contains("PersonIds=p1")
    }

    @Test fun `personImage 返回带鉴权头的头像 URL`() = runTest {
        val img = source.personImage("p1", ImageSize.SMALL) as ImageSource.Remote
        assertThat(img.url).contains("/Items/p1/Images/Primary")
        assertThat(img.url).doesNotContain("api_key")
        assertThat(img.headers["X-Emby-Token"]).isEqualTo("T")
    }
}
