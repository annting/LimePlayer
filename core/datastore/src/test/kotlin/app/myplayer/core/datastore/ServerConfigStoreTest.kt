package app.myplayer.core.datastore

import app.myplayer.core.model.MutablePrivacyVisibilityPolicy
import app.myplayer.core.model.ShowPrivate
import app.myplayer.core.model.SourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ServerConfigStoreTest {
    private fun config(id: String, name: String = "Home", isPrivate: Boolean = false) = ServerConfig(
        id = id,
        sourceType = SourceType.EMBY,
        name = name,
        baseUrl = "http://10.0.0.$id:8096",
        isPrivate = isPrivate,
    )

    @Test fun `upsert then observe`() = runTest {
        val store = InMemoryServerConfigStore()
        store.upsert(config("2"))
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

    // ---- 档一：隐私源默认不可见（docs/隐私模式方案.md §三）----

    @Test fun `普通模式看不到隐私源`() = runTest {
        val store = InMemoryServerConfigStore()
        store.upsert(config("1", name = "正常"))
        store.upsert(config("2", name = "隐私", isPrivate = true))

        val visible = store.observeAll().first()
        assertThat(visible.map { it.name }).containsExactly("正常")
    }

    @Test fun `隐私模式可见全部源`() = runTest {
        val store = InMemoryServerConfigStore(ShowPrivate)
        store.upsert(config("1", name = "正常"))
        store.upsert(config("2", name = "隐私", isPrivate = true))

        assertThat(store.observeAll().first().map { it.name })
            .containsExactly("正常", "隐私")
    }

    @Test fun `切换策略后列表立即随之变化`() = runTest {
        val privacy = MutablePrivacyVisibilityPolicy()
        val store = InMemoryServerConfigStore(privacy)
        store.upsert(config("1", name = "正常"))
        store.upsert(config("2", name = "隐私", isPrivate = true))

        assertThat(store.observeAll().first()).hasSize(1)

        privacy.enter()
        assertThat(store.observeAll().first()).hasSize(2)

        privacy.exit()
        assertThat(store.observeAll().first()).hasSize(1)
    }

    @Test fun `普通模式下写普通源不会冲掉隐藏的隐私源`() = runTest {
        val privacy = MutablePrivacyVisibilityPolicy()
        val store = InMemoryServerConfigStore(privacy)
        store.upsert(config("1", name = "正常"))
        store.upsert(config("2", name = "隐私", isPrivate = true))

        // 普通模式只能看见 1，此时改它 —— 隐藏的 2 必须原样保留
        store.upsert(config("1", name = "改名"))
        privacy.enter()
        assertThat(store.observeAll().first().map { it.name })
            .containsExactly("改名", "隐私")
    }
}
