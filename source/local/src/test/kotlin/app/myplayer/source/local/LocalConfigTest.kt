package app.myplayer.source.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 本机源纯函数：扩展名 / 路径 id / 库 id（`docs/12-本机视频播放方案.md`）。 */
class LocalConfigTest {

    @Test
    fun `video extensions match the 14 common containers`() {
        listOf("mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "webm", "ts", "m2ts", "mpg", "mpeg", "iso", "rmvb")
            .forEach { assertThat("a.$it".isVideoFile()).isTrue() }
        assertThat("a.txt".isVideoFile()).isFalse()
        assertThat("a.srt".isVideoFile()).isFalse()
        assertThat("noext".isVideoFile()).isFalse()
    }

    @Test
    fun `extension check is case insensitive`() {
        assertThat("Movie.MKV".isVideoFile()).isTrue()
        assertThat("Movie.Mp4".isVideoFile()).isTrue()
    }

    @Test
    fun `id round trips through url safe base64`() {
        val path = "电影/沙丘 (2021).mkv"
        val id = encodeLocalId(path)
        assertThat(id).doesNotContain("/")
        assertThat(decodeLocalId(id)).isEqualTo(path)
    }

    @Test
    fun `decode invalid id falls back to raw value`() {
        assertThat(decodeLocalId("not-base64!")).isEqualTo("not-base64!")
    }

    @Test
    fun `library id round trips`() {
        assertThat(localLibraryId("Movies")).isEqualTo("local:Movies")
        assertThat(localFolderOf("local:Movies")).isEqualTo("Movies")
        assertThat(localFolderOf("local:")).isEqualTo("")
        assertThat(localFolderOf(null)).isNull()
    }

    @Test
    fun `join relative path keeps root flat`() {
        assertThat(joinRelative("", "a.mkv")).isEqualTo("a.mkv")
        assertThat(joinRelative("Movies", "a.mkv")).isEqualTo("Movies/a.mkv")
    }

    /** 多根下「根目录自身」也是库：`joinRelative(alias, "")` 不能拼出带尾斜杠的 `Movies/`。 */
    @Test
    fun `join relative path never leaves a trailing slash`() {
        assertThat(joinRelative("Movies", "")).isEqualTo("Movies")
        assertThat(joinRelative("", "")).isEqualTo("")
        // 拼两次也不能出现 `Movies//a.mp4`（真机预览图抽帧失败就是这个）
        assertThat(joinRelative(joinRelative("Movies", ""), "a.mp4")).isEqualTo("Movies/a.mp4")
    }

    @Test
    fun `title strips extension`() {
        assertThat(titleOf("沙丘.2021.mkv")).isEqualTo("沙丘.2021")
        assertThat(titleOf("noext")).isEqualTo("noext")
    }
}
