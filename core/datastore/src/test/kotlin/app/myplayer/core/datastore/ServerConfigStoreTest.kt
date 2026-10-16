package app.myplayer.core.datastore

import app.myplayer.core.model.SourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ServerConfigStoreTest {
    @Test fun `upsert then observe`() = runTest {
        val store = InMemoryServerConfigStore()
        store.upsert(ServerConfig(id = "a", sourceType = SourceType.EMBY, name = "Home", baseUrl = "http://10.0.0.2:8096"))
        val all = store.observeAll().first()
        assertThat(all).hasSize(1)
        assertThat(all.first().baseUrl).isEqualTo("http://10.0.0.2:8096")
    }

    @Test fun `same id updates not duplicates`() = runTest {
        val store = InMemoryServerConfigStore()
        store.upsert(ServerConfig("a", SourceType.EMBY, "Home", "http://x"))
        store.upsert(ServerConfig("a", SourceType.EMBY, "Home2", "http://y"))
        assertThat(store.observeAll().first()).hasSize(1)
        assertThat(store.observeAll().first().first().baseUrl).isEqualTo("http://y")
    }
}
