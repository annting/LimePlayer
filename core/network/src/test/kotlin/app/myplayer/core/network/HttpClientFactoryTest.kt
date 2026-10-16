package app.myplayer.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class HttpClientFactoryTest {
    private lateinit var server: MockWebServer

    @Before fun setup() { server = MockWebServer().apply { start() } }
    @After fun teardown() { server.shutdown() }

    @Test fun `auth interceptor attaches headers`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        val client: OkHttpClient = HttpClientFactory.create(
            config = NetworkConfig(tokenProvider = { mapOf("X-Emby-Token" to "T1") })
        )
        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().use { }
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("X-Emby-Token")).isEqualTo("T1")
    }

    @Test fun `toApiException maps 401`() {
        assertThat(ApiException.Companion.fromStatus(401, "")).isInstanceOf(ApiException.Unauthorized::class.java)
    }

    @Test fun `toApiException maps 403 to unauthorized per contract`() {
        assertThat(ApiException.Companion.fromStatus(403, "")).isInstanceOf(ApiException.Unauthorized::class.java)
    }

    @Test fun `toApiException maps 404`() {
        assertThat(ApiException.Companion.fromStatus(404, "")).isInstanceOf(ApiException.NotFound::class.java)
    }

    @Test fun `toApiException maps 5xx to server error`() {
        val e = ApiException.Companion.fromStatus(503, "oops")
        assertThat(e).isInstanceOf(ApiException.ServerError::class.java)
        assertThat((e as ApiException.ServerError).body).isEqualTo("oops")
    }

    @Test fun `toApiException maps other codes to unknown`() {
        val e = ApiException.Companion.fromStatus(418, "teapot")
        assertThat(e).isInstanceOf(ApiException.Unknown::class.java)
        assertThat((e as ApiException.Unknown).code).isEqualTo(418)
    }
}
