package app.myplayer.source.fnos

import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.ItemQuery
import app.myplayer.core.model.PlayState
import app.myplayer.core.model.PlaybackPlan
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * FnSource 的映射逻辑：用真实响应形状（取自 2026-10-18 对 192.168.31.222:5666 的实测）做夹具。
 *
 * 重点覆盖「电影 = Directory 文件夹条目，真正可播的是 play_item_guid 指向的叶节点」这一飞牛特性。
 */
class FnSourceTest {

    private lateinit var server: MockWebServer
    private val caps = ClientCapabilities(
        supportedVideoCodecs = setOf("h264", "hevc"),
        supportedAudioCodecs = setOf("aac"),
        maxWidth = 3840,
        maxHeight = 2160,
        supportsHdr = true,
        deviceName = "test",
        deviceId = "d1",
    )

    @Before fun setup() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun source(): FnSource {
        val client = FnClient(
            baseUrl = base(),
            secret = "TEST_SECRET",
            apiKey = "TEST_KEY",
            client = OkHttpClient(),
        )
        client.setToken("TK")
        return FnSource(client = client, baseUrl = base(), serverId = "fn1")
    }

    private fun enqueueFolderAndPlayInfo() {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"","data":{"guid":"F1","title":"跆拳道","type":"Directory",
                "play_item_guid":"V1","poster_list":["/d5/07/poster-x.webp"],
                "is_watched":1,"ts":12,"duration":0,"media_stream":{"resolutions":["4k"]},
                "ancestor_guid":"MDB1","ancestor_category":"Movie"}}""".trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"","data":{"guid":"V1","type":"Video","ts":12,
                "media_guid":"M1","video_guid":"VD1","audio_guid":"A1","subtitle_guid":"",
                "play_config":{"skip_opening":null,"skip_ending":null},
                "item":{"guid":"V1","title":"VID_1.mp4","duration":49}}}""".trimIndent()
            )
        )
    }

    @Test fun `文件夹条目下钻叶节点并映射时长与续播位置`() = runBlocking {
        enqueueFolderAndPlayInfo()
        val detail = source().getItemDetail("F1")

        assertThat(detail.id).isEqualTo("F1")
        // 列表/播放页显示的是文件夹标题，而不是文件名
        assertThat(detail.title).isEqualTo("跆拳道")
        // 时长来自叶节点的 play/info.item.duration（秒 → ticks）
        assertThat(detail.durationTicks.value).isEqualTo(49L * 10_000_000L)
        // 续播位置来自 play/info.ts
        assertThat(detail.userData.resumePositionTicks.value).isEqualTo(12L * 10_000_000L)
        assertThat(detail.userData.isPlayed).isTrue()
        assertThat(detail.hasPoster).isTrue()
        assertThat(detail.libraryId).isEqualTo("MDB1")

        // 请求顺序：先 item/{guid}，再 play/info
        assertThat(server.takeRequest().path).isEqualTo("/v/api/v1/item/F1")
        assertThat(server.takeRequest().path).isEqualTo("/v/api/v1/play/info")
    }

    @Test fun `播放计划把相对 play_link 拼成绝对地址`() = runBlocking {
        enqueueFolderAndPlayInfo()
        server.enqueue(MockResponse().setBody("""{"code":0,"msg":"","data":{"play_link":"/v/media/abc/preset.m3u8"}}"""))

        val src = source()
        val detail = src.getItemDetail("F1")
        val plan = src.planPlayback(detail, caps)

        assertThat(plan).isInstanceOf(PlaybackPlan.Direct::class.java)
        val direct = plan as PlaybackPlan.Direct
        assertThat(direct.uri).isEqualTo("${base()}/v/media/abc/preset.m3u8")
        assertThat(direct.mediaSourceId).isEqualTo("M1")
        // HLS 流也要鉴权：不带 Authorization 服务端会返回 JSON「Auth Failed」导致播放黑屏
        assertThat(direct.headers["Authorization"]).isEqualTo("TK")
    }

    @Test fun `海报走 sys-img 接口并带鉴权头`() = runBlocking {
        enqueueFolderAndPlayInfo()
        val src = source()
        val detail = src.getItemDetail("F1")

        val image = src.image(detail, ImageKind.POSTER, ImageSize.MEDIUM)
        assertThat(image).isInstanceOf(ImageSource.Remote::class.java)
        val remote = image as ImageSource.Remote
        assertThat(remote.url).isEqualTo("${base()}/v/api/v1/sys/img/d5/07/poster-x.webp?w=400")
        assertThat(remote.headers["Authorization"]).isEqualTo("TK")
    }

    @Test fun `无海报的条目不请求图片`() = runBlocking {
        val item = app.myplayer.core.model.MediaItemDto(
            id = "X1", serverId = "fn1", sourceType = app.myplayer.core.model.SourceType.FNOS,
            title = "无图", hasPoster = false,
        )
        assertThat(source().image(item, ImageKind.POSTER, ImageSize.SMALL)).isEqualTo(ImageSource.None)
    }

    @Test fun `详情透出分辨率档位与评分`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"","data":{"guid":"F2","title":"有元数据的片","type":"Directory",
                "play_item_guid":"V2","poster_list":["/d5/07/poster-y.webp"],
                "is_watched":0,"watched_ts":0,"duration":0,
                "media_stream":{"resolutions":["480p"]},"vote_average":"7.5"}}""".trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"","data":{"guid":"V2","type":"Video","ts":0,
                "media_guid":"M2","video_guid":"VD2","audio_guid":"A2","subtitle_guid":"",
                "play_config":{"skip_opening":null,"skip_ending":null},
                "item":{"guid":"V2","title":"V2.mp4","duration":100}}}""".trimIndent()
            )
        )
        val detail = source().getItemDetail("F2")

        // 档位原串直接展示（飞牛无精确像素），像素高度给排序
        assertThat(detail.resolutionLabel).isEqualTo("480p")
        assertThat(detail.height).isEqualTo(480)
        // vote_average 是字符串；"7.5" → 7.5f
        assertThat(detail.communityRating).isEqualTo(7.5f)
    }

    @Test fun `未刮削的评分零值视为无评分`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"","data":{"guid":"F3","title":"无刮削","type":"Directory",
                "play_item_guid":"V3","poster_list":["/d5/07/poster-z.webp"],
                "is_watched":0,"watched_ts":0,"duration":0,
                "media_stream":{"resolutions":["1080p"]},"vote_average":"0"}}""".trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"","data":{"guid":"V3","type":"Video","ts":0,
                "media_guid":"M3","video_guid":"VD3","audio_guid":"A3","subtitle_guid":"",
                "play_config":{"skip_opening":null,"skip_ending":null},
                "item":{"guid":"V3","title":"V3.mp4","duration":10}}}""".trimIndent()
            )
        )
        val detail = source().getItemDetail("F3")
        assertThat(detail.communityRating).isNull()
        assertThat(detail.resolutionLabel).isEqualTo("1080p")
    }

    @Test fun `列表映射分辨率档位与观看状态`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"","data":{"total":1,"list":[{"guid":"F1","title":"跆拳道",
                "type":"Directory","poster_list":["/d5/07/poster-x.webp"],"watched":2,"ts":30,
                "duration":0,"season_number":0,"episode_number":0,
                "ancestor_guid":"MDB1","ancestor_category":"Movie",
                "media_stream":{"resolutions":["4k"]}}]}}""".trimIndent()
            )
        )
        val page = source().getItems(ItemQuery(libraryId = "MDB1", limit = 20))

        assertThat(page.totalCount).isEqualTo(1)
        val item = page.items.single()
        assertThat(item.title).isEqualTo("跆拳道")
        assertThat(item.height).isEqualTo(2160)          // "4k" → 2160
        assertThat(item.playCount).isEqualTo(2)
        assertThat(item.userData.isPlayed).isTrue()
        assertThat(item.hasPoster).isTrue()
        assertThat(item.libraryId).isEqualTo("MDB1")
    }

    @Test fun `播放地址短期缓存避免重复起转码会话`() = runBlocking {
        enqueueFolderAndPlayInfo()
        server.enqueue(MockResponse().setBody("""{"code":0,"msg":"","data":{"play_link":"/v/media/abc/preset.m3u8"}}"""))

        val src = source()
        val detail = src.getItemDetail("F1")
        src.planPlayback(detail, caps)
        val afterFirst = server.requestCount          // item + play/info + play/play = 3

        // 第二次协商应命中缓存，不再打 play/play（否则服务端会再起一个转码会话）
        src.planPlayback(detail, caps)
        assertThat(server.requestCount).isEqualTo(afterFirst)
    }

    @Test fun `进度上报带叶节点 guid 秒数与 play_link`() = runBlocking {
        enqueueFolderAndPlayInfo()
        server.enqueue(MockResponse().setBody("""{"code":0,"msg":"","data":{"play_link":"/v/media/abc/preset.m3u8"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"msg":"","data":null}"""))

        val src = source()
        val detail = src.getItemDetail("F1")
        src.planPlayback(detail, caps)      // 产生 play_link，缓存给上报用
        src.reportProgress(detail, 30.seconds, PlayState.PLAYING)

        // item/ → play/info → play/play → record
        repeat(3) { server.takeRequest() }
        val record = server.takeRequest()
        assertThat(record.path).isEqualTo("/v/api/v1/play/record")
        val body = record.body.readUtf8()
        assertThat(body).contains("\"item_guid\":\"V1\"")     // 上报叶节点，不是文件夹
        assertThat(body).contains("\"media_guid\":\"M1\"")
        assertThat(body).contains("\"ts\":30")
        assertThat(body).contains("\"duration\":49")
        // play_link 必传：缺了接口照样 code:0，但进度不会落库（2026-10-18 实测）
        assertThat(body).contains("\"play_link\":\"/v/media/abc/preset.m3u8\"")
    }
}
