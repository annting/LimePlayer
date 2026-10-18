package app.myplayer.source.emby

import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.PlaybackPlan
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 外挂字幕下发（任务：字幕渲染）：PlaybackInfo 的文本字幕流 → plan.subtitles
 * 按需转 srt 地址 + 正确 mimeType。
 */
class SubtitleDeliveryTest {

    private lateinit var server: MockWebServer
    private lateinit var source: EmbySource

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        source = EmbySource.create(
            client = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
            serverId = "s1",
            token = "TOKEN",
            userId = "u1",
            deviceId = "d1",
        )
    }

    @After fun teardown() = server.shutdown()

    private val item = MediaItemDto(
        id = "v1", serverId = "s1", sourceType = SourceType.EMBY, title = "片",
        durationTicks = Ticks(1),
    )
    private val caps = ClientCapabilities(
        supportedVideoCodecs = setOf("h264"), supportedAudioCodecs = setOf("aac"),
        maxWidth = 3840, maxHeight = 2160, supportsHdr = true, deviceName = "t", deviceId = "d",
    )

    private fun playbackInfoBody(subtitleStreams: String) = """
        {"MediaSources":[{
            "Id":"ms1","SupportsDirectPlay":true,"SupportsDirectStream":true,
            "MediaStreams":[
                {"Type":"Video","Index":0,"Codec":"h264"},
                {"Type":"Audio","Index":1,"Codec":"aac"},
                $subtitleStreams
            ]
        }],"PlaySessionId":"ps1"}
    """.trimIndent()

    @Test fun `文本字幕流转成外挂 srt 地址`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                playbackInfoBody(
                    """{"Type":"Subtitle","Index":2,"Codec":"subrip","Language":"chi",
                        "DisplayTitle":"中文（简体）","IsExternal":true,
                        "IsTextSubtitleStream":true,"SupportsExternalStream":true}"""
                )
            )
        )
        val plan = source.planPlayback(item, caps) as PlaybackPlan.Direct
        assertThat(plan.subtitles).hasSize(1)
        val sub = plan.subtitles.single()
        // Emby 外挂字幕端点：按需转 srt，api_key 鉴权
        assertThat(sub.url).contains("/Videos/v1/ms1/Subtitles/2/Stream.srt")
        assertThat(sub.url).contains("api_key=TOKEN")
        assertThat(sub.mimeType).isEqualTo("application/x-subrip")
        assertThat(sub.language).isEqualTo("chi")
        assertThat(sub.label).isEqualTo("中文（简体）")
        // 音轨 / 视频流不进字幕列表
    }

    @Test fun `图形字幕（PGS）不下发外挂`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                playbackInfoBody(
                    """{"Type":"Subtitle","Index":2,"Codec":"pgssub","Language":"jpn",
                        "IsExternal":false,"IsTextSubtitleStream":false,
                        "SupportsExternalStream":false}"""
                )
            )
        )
        val plan = source.planPlayback(item, caps) as PlaybackPlan.Direct
        assertThat(plan.subtitles).isEmpty()   // 图形字幕只能转码烧录
    }

    @Test fun `vtt 字幕映射正确 mime 且默认轨带标记`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                playbackInfoBody(
                    """{"Type":"Subtitle","Index":3,"Codec":"webvtt","Language":"eng",
                        "Title":"English","IsExternal":true,"IsTextSubtitleStream":true,
                        "SupportsExternalStream":true,"IsDefault":true}"""
                )
            )
        )
        val plan = source.planPlayback(item, caps) as PlaybackPlan.Direct
        val sub = plan.subtitles.single()
        assertThat(sub.mimeType).isEqualTo("text/vtt")
        assertThat(sub.isDefault).isTrue()
        assertThat(sub.label).isEqualTo("English")
    }

    @Test fun `无字幕流时列表为空`() = runTest {
        server.enqueue(MockResponse().setBody(playbackInfoBody(
            """{"Type":"Video","Index":0,"Codec":"h264"}"""
        )))
        val plan = source.planPlayback(item, caps) as PlaybackPlan.Direct
        assertThat(plan.subtitles).isEmpty()
    }
}
