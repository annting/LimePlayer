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
}
