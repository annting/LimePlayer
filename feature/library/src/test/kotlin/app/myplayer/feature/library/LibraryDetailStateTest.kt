package app.myplayer.feature.library

import app.myplayer.feature.library.home.HomeSourceType
import app.myplayer.feature.library.home.MockHomeData
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 媒体库详情页的状态层用例：多选状态机 + SMB Mock 映射。
 *
 * 多选是「长按进入、点选切换、关闭退出」的小状态机，规则简单但写错
 * （比如最后一个取消后不退出、退出不清空）只有上手点才能发现 —— 纯函数级钉住。
 */
class LibraryDetailStateTest {

    // ---- 进入 / 退出 ----

    @Test fun `长按进入多选并选中那一项`() {
        val s = DetailSelection().enter("a")
        assertThat(s.active).isTrue()
        assertThat(s.ids).containsExactly("a")
    }

    @Test fun `多选中重复长按是幂等的`() {
        val s = DetailSelection().enter("a").toggle("b").enter("c")
        assertThat(s.ids).containsExactly("a", "b")
    }

    @Test fun `退出多选时清空选中集`() {
        val s = DetailSelection().enter("a").toggle("b").exit()
        assertThat(s.active).isFalse()
        assertThat(s.ids).isEmpty()
    }

    // ---- 切换 ----

    @Test fun `点选切换再点取消`() {
        val s = DetailSelection().enter("a").toggle("b")
        assertThat(s.ids).containsExactly("a", "b")
        val back = s.toggle("b")
        assertThat(back.ids).containsExactly("a")
        assertThat(back.active).isTrue()
    }

    @Test fun `最后一个取消后自动退出多选`() {
        val s = DetailSelection().enter("a")
        assertThat(s.toggle("a").active).isFalse()
        assertThat(s.toggle("a").ids).isEmpty()
    }

    @Test fun `未进入多选时 toggle 是空操作`() {
        val s = DetailSelection()
        assertThat(s.toggle("a")).isEqualTo(s)
    }

    // ---- 全选 ----

    @Test fun `全选覆盖所有项且计数正确`() {
        val s = DetailSelection().enter("a").selectAll(listOf("a", "b", "c"))
        assertThat(s.active).isTrue()
        assertThat(s.count).isEqualTo(3)
    }

    // ---- SMB Mock 映射 ----

    @Test fun `SMB Mock 按文件夹名取数且路径与名字对应`() {
        val videos = LibraryDetailMock.smbVideos("smb:Movies")
        assertThat(videos).isNotEmpty()
        assertThat(videos.all { it.path.startsWith("/media/Movies/") }).isTrue()
        assertThat(videos.all { it.name.endsWith(".mkv") }).isTrue()
        // 首页 Mock 的 Movies 有 5 个文件
        assertThat(videos).hasSize(5)
    }

    @Test fun `SMB Mock 未知文件夹返回空`() {
        assertThat(LibraryDetailMock.smbVideos("smb:NoSuchFolder")).isEmpty()
    }

    @Test fun `sourceType 两个值都在（防误删）`() {
        assertThat(HomeSourceType.entries).containsExactly(HomeSourceType.EMBY, HomeSourceType.SMB)
    }
}
