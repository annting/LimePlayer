package app.myplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 多根授权的路径前缀规则（`docs/12-本机视频播放方案.md` §4.1）。
 *
 * 这些字符串**会落进 `file_media_items.path` / `playback_progress.mediaKey`**，
 * 一旦解析口径变了，历史断点就全部对不上，所以把边界钉在单测里。
 */
class LocalTreeKeysTest {

    private val ext = "content://com.android.externalstorage.documents"

    @Test
    fun `内部存储子目录 取目录作为别名`() {
        assertThat(treeAliasOf("$ext/tree/primary%3AMovies")).isEqualTo("Movies")
    }

    @Test
    fun `内部存储根 别名是空串`() {
        assertThat(treeAliasOf("$ext/tree/primary%3A")).isEqualTo("")
    }

    @Test
    fun `嵌套目录 保留层级`() {
        assertThat(treeAliasOf("$ext/tree/primary%3ADownload%2Fvideo")).isEqualTo("Download/video")
    }

    @Test
    fun `外部SD卡 保留卷前缀`() {
        val uri = "$ext/tree/1A2B-3C4D%3AMovies"
        assertThat(treeAliasOf(uri)).isEqualTo("1A2B-3C4D:Movies")
        assertThat(treeDisplayNameOf(uri)).isEqualTo("Movies")
    }

    @Test
    fun `展示名 取末段 卷根显示本机存储`() {
        assertThat(displayNameOfAlias("")).isEqualTo("本机存储")
        assertThat(displayNameOfAlias("Download/video")).isEqualTo("video")
        assertThat(displayNameOfAlias("Movies")).isEqualTo("Movies")
    }

    @Test
    fun `同族根互相覆盖 只保留最宽的一根`() {
        // 同一条
        assertThat(aliasCovers("Movies", "Movies")).isTrue()
        // 祖先覆盖后代
        assertThat(aliasCovers("Movies", "Movies/2024")).isTrue()
        assertThat(aliasCovers("Movies/2024", "Movies")).isTrue()
        // 卷根覆盖该卷内一切
        assertThat(aliasCovers("", "Movies")).isTrue()
        assertThat(aliasCovers("Movies", "")).isTrue()
    }

    @Test
    fun `不相干的根不互相覆盖`() {
        assertThat(aliasCovers("Movies", "Download")).isFalse()
        // 前缀相同但不是层级关系：必须并存，否则会误删用户的授权
        assertThat(aliasCovers("Movies", "Movies2")).isFalse()
        assertThat(aliasCovers("1A2B-3C4D:Movies", "Movies")).isFalse()
    }
}
