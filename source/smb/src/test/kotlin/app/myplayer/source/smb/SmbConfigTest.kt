package app.myplayer.source.smb

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SmbConfigTest {

    @Test
    fun `解析落库格式 smb 冒号端口斜杠共享`() {
        val c = SmbConfig.parse("smb://192.168.31.15:445/video", username = "u", password = "p")
        assertThat(c).isNotNull()
        assertThat(c!!.host).isEqualTo("192.168.31.15")
        assertThat(c.port).isEqualTo(445)
        assertThat(c.share).isEqualTo("video")
        assertThat(c.baseUrl).isEqualTo("smb://192.168.31.15:445/video")
    }

    @Test
    fun `兼容 Windows 反斜杠写法且取第一段作为共享名`() {
        val c = SmbConfig.parse("\\\\192.168.31.15\\video\\Movies")
        assertThat(c!!.share).isEqualTo("video")
        assertThat(c.host).isEqualTo("192.168.31.15")
    }

    @Test
    fun `缺共享目录时返回 null`() {
        assertThat(SmbConfig.parse("smb://192.168.31.15")).isNull()
        assertThat(SmbConfig.parse("")).isNull()
    }

    @Test
    fun `自定义端口`() {
        val c = SmbConfig.parse("smb://nas:1445/media")
        assertThat(c!!.port).isEqualTo(1445)
    }

    @Test
    fun `urlFor 拼接相对路径`() {
        val c = SmbConfig(host = "nas", share = "video")
        assertThat(c.urlFor("")).isEqualTo("smb://nas:445/video")
        assertThat(c.urlFor("/Movies/沙丘.mkv")).isEqualTo("smb://nas:445/video/Movies/沙丘.mkv")
    }
}

class SmbRangeTest {

    @Test
    fun `完整区间`() {
        assertThat(parseRange("bytes=0-99", 1000)).isEqualTo(0L to 99L)
    }

    @Test
    fun `开放式结尾`() {
        assertThat(parseRange("bytes=500-", 1000)).isEqualTo(500L to 999L)
    }

    @Test
    fun `后缀区间取最后 N 字节`() {
        assertThat(parseRange("bytes=-200", 1000)).isEqualTo(800L to 999L)
    }

    @Test
    fun `越界与脏数据按整文件处理`() {
        assertThat(parseRange("bytes=1000-", 1000)).isNull()
        assertThat(parseRange("bytes=abc-def", 1000)).isNull()
        assertThat(parseRange(null, 1000)).isNull()
        assertThat(parseRange("bytes=0-99", 0)).isNull()
    }

    @Test
    fun `结束位置超出总长时收敛到末尾`() {
        assertThat(parseRange("bytes=900-99999", 1000)).isEqualTo(900L to 999L)
    }

    @Test
    fun `拒绝对不上的单位`() {
        assertThat(parseRange("items=0-1", 1000)).isNull()
    }
}

class SmbHelpersTest {

    @Test
    fun `视频扩展名判定`() {
        assertThat("a.MKV".isVideoFile()).isTrue()
        assertThat("b.mp4".isVideoFile()).isTrue()
        assertThat("c.srt".isVideoFile()).isFalse()
        assertThat("README".isVideoFile()).isFalse()
    }

    @Test
    fun `mime 兜底`() {
        assertThat(mimeTypeOf("x.mkv")).isEqualTo("video/x-matroska")
        assertThat(mimeTypeOf("x.unknown")).isEqualTo("application/octet-stream")
    }
}

class SmbItemIdTest {

    private val source = SmbMediaSource(SmbConfig(host = "nas", share = "video"), serverId = "s1")

    @Test
    fun `条目 id 是 URL 安全的 Base64 且可还原真实路径`() {
        val path = "减肥/锻炼腹肌 (1).webm"
        val id = source.encodeId(path)

        // 关键约束：不能含 `/`，否则 `detail/{id}` 这类 route 会被拆成多段 → 导航崩
        assertThat(id).doesNotContain("/")
        assertThat(id).doesNotContain("+")
        assertThat(id).doesNotContain("=")
        assertThat(source.decodeId(id)).isEqualTo(path)
    }

    @Test
    fun `脏 id 原样返回，不抛异常`() {
        assertThat(source.decodeId("not-base64!!")).isEqualTo("not-base64!!")
    }
}
