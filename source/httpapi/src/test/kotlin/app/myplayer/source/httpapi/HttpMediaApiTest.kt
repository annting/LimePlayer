package app.myplayer.source.httpapi

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class HttpMediaApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HttpMediaApi

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        api = HttpMediaApi(
            client = OkHttpClient(),
            config = SourceConfig(
                serverId = "s1", baseUrl = server.url("/").toString().trimEnd('/'),
                token = "T", userId = "u1",           // 必须给 userId：取数接口会 requireNotNull
                deviceId = "d1", clientName = "myplayer",
            ),
        )
    }
    @After fun teardown() { server.shutdown() }

    @Test fun `getItems parses response into dtos`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"i1","Name":"Le Film","RunTimeTicks":60000000}],"TotalRecordCount":1}"""))
        val page = api.getItems(libraryId = "lib1", startIndex = 0, limit = 50)
        assertThat(page.totalCount).isEqualTo(1)
        assertThat(page.items.first().id).isEqualTo("i1")
        assertThat(page.items.first().name).isEqualTo("Le Film")
    }

    @Test fun `列表查询默认不请求 People 字段`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}"""))
        api.getItems(libraryId = "lib1", startIndex = 0, limit = 50)
        assertThat(server.takeRequest().path).doesNotContain("People")

        server.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}"""))
        api.getItems(libraryId = "lib1", startIndex = 0, limit = 50, includePeople = true)
        assertThat(server.takeRequest().path).contains("People")
    }

    @Test fun `getViews 走 Views 专用端点`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"lib1","Name":"电影","CollectionType":"movies","ChildCount":42}],"TotalRecordCount":1}"""))
        val views = api.getViews()
        assertThat(server.takeRequest().path).startsWith("/Users/u1/Views")
        assertThat(views.items.first().collectionType).isEqualTo("movies")
        assertThat(views.items.first().childCount).isEqualTo(42)
    }

    @Test fun `authenticate sends body and parses token`() = runTest {
        server.enqueue(MockResponse().setBody("""{"AccessToken":"TK","User":{"Id":"u1","Name":"bob"}}"""))
        val result = api.authenticateByName("bob", "pw")
        assertThat(result.accessToken).isEqualTo("TK")
        assertThat(result.user.id).isEqualTo("u1")
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/Users/AuthenticateByName")
        assertThat(req.method).isEqualTo("POST")
    }

    @Test fun `image url 带尺寸但不带令牌`() = runTest {
        val url = api.imageUrl(itemId = "i1", kind = "Primary", maxWidth = 400)
        assertThat(url).contains("/Items/i1/Images/Primary")
        assertThat(url).contains("maxWidth=400")
        assertThat(url).doesNotContain("api_key")   // 令牌走 headers，不进 URL / Coil 缓存键
    }

    @Test fun `getEpisodes 默认不带图不带简介`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"e1","ParentIndexNumber":1,"IndexNumber":2}],"TotalRecordCount":1}"""))
        val episodes = api.getEpisodes(seriesId = "s1", userId = "u1")
        assertThat(episodes.single().parentIndexNumber).isEqualTo(1)
        val path = server.takeRequest().path!!
        assertThat(path).startsWith("/Shows/s1/Episodes")
        assertThat(path).contains("EnableImages=false")
        assertThat(path).doesNotContain("Overview")
    }

    @Test fun `getEpisodes 详情页带图并补简介字段`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"e1","Overview":"第一集","ProductionYear":2019}],"TotalRecordCount":1}"""))
        val episodes = api.getEpisodes(seriesId = "s1", userId = "u1", withDetailFields = true)
        assertThat(episodes.single().overview).isEqualTo("第一集")
        val path = server.takeRequest().path!!
        assertThat(path).contains("EnableImages=true")
        assertThat(path).contains("Overview")
    }

    // ---- 详情页写操作与人物页（`05` §2.2，详情页二期）----

    @Test fun `markPlayed 用 POST PlayedItems 并解析权威 UserData`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Played":true,"PlaybackPositionTicks":0,"IsFavorite":false,"PlayCount":3}"""))
        val dto = api.markPlayed(itemId = "i1", userId = "u1")
        assertThat(dto.played).isTrue()
        assertThat(dto.playCount).isEqualTo(3)
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/Users/u1/PlayedItems/i1")
        assertThat(req.method).isEqualTo("POST")
    }

    @Test fun `unmarkPlayed 用 DELETE 同路径`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Played":false,"PlayCount":0}"""))
        val dto = api.unmarkPlayed(itemId = "i1", userId = "u1")
        assertThat(dto.played).isFalse()
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/Users/u1/PlayedItems/i1")
        assertThat(req.method).isEqualTo("DELETE")
    }

    /** 删除条目：204 无响应体也能成功（不走 execute 的 JSON 解析）。 */
    @Test fun `deleteItem 成功于 204 无响应体`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        api.deleteItem("i1")
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/Items/i1")
        assertThat(req.method).isEqualTo("DELETE")
    }

    /** 无权限（403）时上抛 ApiException，由 UI 呈现错误而不是默默成功。 */
    @Test fun `deleteItem 无权限时抛 ApiException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val error = runCatching { api.deleteItem("i1") }.exceptionOrNull()
        assertThat(error).isInstanceOf(app.myplayer.core.network.ApiException::class.java)
    }

    @Test fun `getPersonWorks 按 PersonIds 过滤`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"m1","Name":"沙丘"}],"TotalRecordCount":1}"""))
        val works = api.getPersonWorks(personId = "p1", userId = "u1")
        assertThat(works.single().id).isEqualTo("m1")
        val path = server.takeRequest().path!!
        assertThat(path).startsWith("/Users/u1/Items")
        assertThat(path).contains("PersonIds=p1")
        assertThat(path).contains("Recursive=true")
    }

    @Test fun `personImageUrl 与条目图片同端点且不带令牌`() {
        val url = api.personImageUrl(personId = "p1", maxWidth = 200)
        assertThat(url).contains("/Items/p1/Images/Primary")
        assertThat(url).contains("maxWidth=200")
        assertThat(url).doesNotContain("api_key")
    }
}
