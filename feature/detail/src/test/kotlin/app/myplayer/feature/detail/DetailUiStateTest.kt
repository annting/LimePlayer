package app.myplayer.feature.detail

import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaStreamInfo
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.StreamKind
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.UserData
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 主按钮文案（`03` §6.3）：未看过 / 已看完「播放」；看了一半「继续播放」+ 续播行。 */
class DetailUiStateTest {

    private fun item(
        durationSeconds: Long = 0,
        resumeSeconds: Long = 0,
        played: Boolean = false,
    ) = MediaItemDto(
        id = "i1", serverId = "srv", sourceType = SourceType.EMBY, title = "沙丘",
        durationTicks = Ticks.ofSeconds(durationSeconds),
        userData = UserData(isPlayed = played, resumePositionTicks = Ticks.ofSeconds(resumeSeconds)),
    )

    @Test fun `未观看时显示播放且无续播行`() {
        val it = item(durationSeconds = 7200)
        assertThat(primaryActionLabel(it)).isEqualTo("播放")
        assertThat(it.resumeState()).isNull()
    }

    @Test fun `已看完且服务端清空位置时显示播放`() {
        // Emby 判定看完会把 PlaybackPositionTicks 清零（真机实测：FigureRobics (1)）
        val it = item(durationSeconds = 7200, resumeSeconds = 0, played = true)
        assertThat(primaryActionLabel(it)).isEqualTo("播放")
        assertThat(it.resumeState()).isNull()
    }

    @Test fun `已标记已看但服务端仍有位置时按位置显示继续播放`() {
        // 只看服务端位置、不看 isPlayed（2026-10-19 用户确认）：看完后重看留下一段进度也要显示
        val it = item(durationSeconds = 7200, resumeSeconds = 1800, played = true)
        assertThat(primaryActionLabel(it)).isEqualTo("继续播放")
        assertThat(it.resumeState()?.remaining).isEqualTo("1小时30分")
    }

    @Test fun `看到一半显示继续播放并给出进度与剩余`() {
        // 2 小时片长、已看 30 分钟 → 进度 25%、剩余 1小时30分
        val it = item(durationSeconds = 7200, resumeSeconds = 1800)
        assertThat(primaryActionLabel(it)).isEqualTo("继续播放")
        val resume = it.resumeState()
        assertThat(resume).isNotNull()
        assertThat(resume!!.fraction).isWithin(0.001f).of(0.25f)
        assertThat(resume.remaining).isEqualTo("1小时30分")
    }

    @Test fun `时长缺失时只显示继续播放不画进度条`() {
        // 飞牛等源可能不给时长：算不出占比与剩余，只保留文案
        val it = item(resumeSeconds = 600)
        assertThat(primaryActionLabel(it)).isEqualTo("继续播放")
        assertThat(it.resumeState()).isNull()
    }

    @Test fun `剩余不足一分钟也按秒显示`() {
        val it = item(durationSeconds = 7200, resumeSeconds = 7190)
        assertThat(it.resumeState()?.remaining).isEqualTo("10秒")
    }

    // ---- 次级按钮文案（`03` §6.3 标记已播放）----

    @Test fun `未看时次级按钮为标记已播放`() {
        assertThat(markPlayedLabel(item())).isEqualTo("标记已播放")
    }

    @Test fun `已看时次级按钮为取消已播放`() {
        assertThat(markPlayedLabel(item(played = true))).isEqualTo("取消已播放")
    }

    // ---- 信息分区（`03` §6.3；取代旧「技术信息」折叠区）----

    @Test fun `文件信息含时长添加日期与完整路径且只含有值的行`() {
        val rows = item(durationSeconds = 1890).copy(
            container = "mkv", bitrate = 20_000_000, sizeBytes = 39_219_535L,
            path = "/volume3/video/Test3/FigureRobics (2).avi",
            // UTC 正午：任何时区都落在同一天，断言不受运行机器时区影响
            dateCreated = java.time.Instant.parse("2021-07-31T12:00:00Z").toEpochMilli(),
        ).fileInfoRows()
        assertThat(rows.map { it.first })
            .containsExactly("容器", "文件大小", "时长", "总码率", "添加日期", "路径").inOrder()
        assertThat(rows).containsAtLeast(
            "容器" to "MKV",
            "文件大小" to "37MB",
            "时长" to "31分30秒",
            "总码率" to "20.0 Mbps",
            "添加日期" to "2021-07-31",
            "路径" to "/volume3/video/Test3/FigureRobics (2).avi",
        )
    }

    @Test fun `时长格式按时长量级切换`() {
        // 45 秒 / 12 分 5 秒 / 1 小时 23 分：全程带秒，整分省掉尾部「0秒」
        assertThat(item(durationSeconds = 45).fileInfoRows().toMap()["时长"]).isEqualTo("45秒")
        assertThat(item(durationSeconds = 725).fileInfoRows().toMap()["时长"]).isEqualTo("12分5秒")
        assertThat(item(durationSeconds = 4980).fileInfoRows().toMap()["时长"]).isEqualTo("1小时23分")
    }

    @Test fun `文件信息全空时不渲染`() {
        assertThat(item().fileInfoRows()).isEmpty()
    }

    /** 逐项 label：编码 / 分辨率 / 帧率 / 位深 / 流码率；多流时 label 追加流号。 */
    @Test fun `视频信息逐项带 label 且多流标注流号`() {
        val it = item().copy(
            mediaStreams = listOf(
                MediaStreamInfo(kind = StreamKind.VIDEO, codec = "hevc", width = 3840, height = 2160, frameRate = 23.976f, bitDepth = 10, bitrate = 20_000_000),
                MediaStreamInfo(kind = StreamKind.VIDEO, codec = "h264", width = 1920, height = 1080),
                MediaStreamInfo(kind = StreamKind.AUDIO, codec = "dts"),
            ),
        )
        val rows = it.videoInfoPairs()
        assertThat(rows).hasSize(7)
        assertThat(rows).containsAtLeast(
            "编码 · 流 1" to "HEVC",
            "分辨率 · 流 1" to "3840 × 2160",
            "帧率 · 流 1" to "24fps",
            "位深 · 流 1" to "10bit",
            "码率 · 流 1" to "20.0 Mbps",
            "编码 · 流 2" to "H264",
            "分辨率 · 流 2" to "1920 × 1080",
        )
    }

    /** 无 MediaStreams 时兜底条目级字段（Emby 4.10 / 飞牛口径，`05` §2.2）。 */
    @Test fun `视频信息无流时兜底条目级编码与分辨率`() {
        assertThat(item().copy(videoCodec = "vp8", width = 720, height = 480).videoInfoPairs())
            .containsExactly("编码" to "VP8", "分辨率" to "720 × 480").inOrder()
        // 飞牛只有档位字符串
        assertThat(item().copy(resolutionLabel = "480p").videoInfoPairs())
            .containsExactly("分辨率" to "480P")
        assertThat(item().videoInfoPairs()).isEmpty()
    }

    /** 逐项 label：编码 / 声道 / 采样率 / 语言；默认轨标「默认」。 */
    @Test fun `音频信息逐项带 label 并标记默认轨`() {
        val rows = item().copy(
            mediaStreams = listOf(
                MediaStreamInfo(kind = StreamKind.AUDIO, codec = "dts", channels = 6, language = "chs", sampleRate = 48_000, isDefault = true),
                MediaStreamInfo(kind = StreamKind.AUDIO, codec = "aac", channels = 2),
            ),
        ).audioInfoPairs()
        assertThat(rows).hasSize(7)
        assertThat(rows).containsAtLeast(
            "编码 · 流 1" to "DTS",
            "声道 · 流 1" to "6 声道",
            "采样率 · 流 1" to "48kHz",
            "语言 · 流 1" to "chs",
            "默认 · 流 1" to "是",
            "编码 · 流 2" to "AAC",
            "声道 · 流 2" to "2 声道",
        )
    }

    @Test fun `音频信息无流时兜底条目级编码列表`() {
        assertThat(item().copy(audioCodecs = listOf("dts", "aac")).audioInfoPairs())
            .containsExactly("编码 · 流 1" to "DTS", "编码 · 流 2" to "AAC").inOrder()
        assertThat(item().copy(audioCodecs = listOf("aac")).audioInfoPairs())
            .containsExactly("编码" to "AAC")
        assertThat(item().audioInfoPairs()).isEmpty()
    }

    /** 逐项 label：语言 / 格式 / 来源（外挂·内嵌）；默认轨标「默认」。 */
    @Test fun `字幕信息逐项带 label 与外挂标记`() {
        val rows = item().copy(
            mediaStreams = listOf(
                MediaStreamInfo(kind = StreamKind.SUBTITLE, codec = "srt", language = "zh", isExternal = true),
                MediaStreamInfo(kind = StreamKind.SUBTITLE, codec = "pgs", language = "eng", isDefault = true),
            ),
        ).subtitleInfoPairs()
        assertThat(rows).hasSize(7)
        assertThat(rows).containsAtLeast(
            "语言 · 流 1" to "zh",
            "格式 · 流 1" to "SRT",
            "来源 · 流 1" to "外挂",
            "语言 · 流 2" to "eng",
            "格式 · 流 2" to "PGS",
            "来源 · 流 2" to "内嵌",
            "默认 · 流 2" to "是",
        )
    }

    @Test fun `无字幕流时不渲染字幕分区`() {
        assertThat(item().subtitleInfoPairs()).isEmpty()
    }
}
