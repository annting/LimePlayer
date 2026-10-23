package app.myplayer.feature.library.home

import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary
import app.myplayer.core.model.SourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 真实数据源的编排与降级测试。
 *
 * 这里验证的是**首页最怕的两类事故**：
 * 1. 某个端点不支持（老服务端没有 `/Items/Latest`）→ 不能把整页判成离线；
 * 2. 库列表拿不到 → 必须整屏进错误态，而不是装作「服务器里没有片」。
 */
class ServerHomeDataSourceTest {

    private fun item(id: String, title: String = id) = MediaItemDto(
        id = id,
        serverId = "srv-test",
        sourceType = SourceType.EMBY,
        title = title,
        hasPoster = true,
    )

    private fun library(id: String, name: String = id, count: Int? = null) =
        MediaLibrary(id = id, name = name, itemCount = count)

    @Test
    fun `三个模块分别按端点取回并组装`() = runTest {
        val source = FakeSource(
            libraries = listOf(library("lib-movie", "电影", 486), library("lib-tv", "电视剧", 214)),
            onItems = { q -> listOf(item("${q.libraryId}-1"), item("${q.libraryId}-2")) },
            resume = listOf(item("cw")),
            latest = listOf(item("ra-1"), item("ra-2")),
        )

        val snap = ServerHomeDataSource(source, "客厅 Emby").load("srv-test")

        assertThat(snap.serverName).isEqualTo("客厅 Emby")
        assertThat(snap.continueWatching.map { it.id }).containsExactly("cw")
        assertThat(snap.libraries.map { it.name }).containsExactly("电影", "电视剧").inOrder()
        // totalCount 用服务端库的 itemCount（「查看全部」据此判断是否值得跳），不是首屏条数
        assertThat(snap.libraries.first().totalCount).isEqualTo(486)
        assertThat(snap.libraries.first().items).hasSize(2)
        assertThat(snap.recentlyAdded).hasSize(2)
    }

    @Test
    fun `库列表失败时整屏失败`() = runTest {
        val err = runCatching {
            ServerHomeDataSource(FakeSource(failLibraries = true), "x").load("srv-test")
        }.exceptionOrNull()

        assertThat(err).isInstanceOf(IllegalStateException::class.java)
        assertThat(err?.message).contains("连接被拒绝")
    }

    @Test
    fun `续播与最近添加失败只丢对应模块`() = runTest {
        val source = FakeSource(
            libraries = listOf(library("lib-movie")),
            onItems = { listOf(item("m1")) },
            failResume = true,
            failLatest = true,
        )

        val snap = ServerHomeDataSource(source, "x").load("srv-test")

        assertThat(snap.continueWatching).isEmpty()
        assertThat(snap.recentlyAdded).isEmpty()
        // 关键：库还在 → hasContent 为真 → 页面不会整屏空白，也不会被误判成离线
        assertThat(snap.libraries).hasSize(1)
    }

    @Test
    fun `空库与取数失败的库都不出横滑行`() = runTest {
        val source = FakeSource(
            libraries = listOf(library("lib-movie"), library("lib-empty"), library("lib-broken")),
            onItems = { q -> if (q.libraryId == "lib-movie") listOf(item("m1")) else emptyList() },
            failLibraryIds = setOf("lib-broken"),
        )

        val snap = ServerHomeDataSource(source, "x").load("srv-test")

        assertThat(snap.libraries.map { it.id }).containsExactly("lib-movie")
    }

    @Test
    fun `作品级为空时退回放开 Episode 再查一次`() = runTest {
        var calls = 0
        val source = FakeSource(
            libraries = listOf(library("lib-tv")),
            onItems = { q ->
                calls++
                if ("Episode" in q.includeItemTypes) listOf(item("ep1")) else emptyList()
            },
        )

        val snap = ServerHomeDataSource(source, "x").load("srv-test")

        assertThat(snap.libraries.single().items.map { it.id }).containsExactly("ep1")
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `海报取 400 档、横图取 800 档`() = runTest {
        val source = FakeSource()
        val data = ServerHomeDataSource(source, "x")

        data.image(item("m1"), ImageKind.POSTER)
        assertThat(source.lastImageSize).isEqualTo(ImageSize.MEDIUM)

        data.image(item("m1"), ImageKind.THUMB)
        assertThat(source.lastImageSize).isEqualTo(ImageSize.LARGE)
    }

    @Test
    fun `源不可用时给出原因而不是空内容`() = runTest {
        val err = runCatching { OfflineHomeDataSource("请重新登录").load("srv-test") }.exceptionOrNull()
        assertThat(err?.message).isEqualTo("请重新登录")
    }
}
