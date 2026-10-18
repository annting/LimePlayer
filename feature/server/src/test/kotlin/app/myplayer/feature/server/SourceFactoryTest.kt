package app.myplayer.feature.server

import app.myplayer.core.datastore.InMemoryServerConfigStore
import app.myplayer.core.datastore.InMemoryTokenStore
import app.myplayer.core.datastore.ServerConfig
import app.myplayer.core.model.SourceType
import app.myplayer.source.emby.EmbySource
import app.myplayer.source.fnos.FnSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import okhttp3.OkHttpClient

class SourceFactoryTest {

    private val factory = SourceFactory(
        client = OkHttpClient(),
        serverConfigStore = InMemoryServerConfigStore(),
        tokenStore = InMemoryTokenStore(),
        deviceId = "d1",
    )

    @Test fun `EMBY 配置可造出 EmbySource 且 serverId 正确`() {
        factory.tokenStore.put("s1", "TK")   // 通过 internal 暴露的 store 注入令牌
        val source = factory.create(
            ServerConfig("s1", SourceType.EMBY, "Home", "http://nas:8096", userId = "u1"),
        )
        assertThat(source).isInstanceOf(EmbySource::class.java)
        assertThat(source!!.serverId).isEqualTo("s1")
        assertThat(source.type).isEqualTo(SourceType.EMBY)
    }

    @Test fun `缺少令牌或 userId 时返回 null 而不是半成品 source`() {
        // 无令牌
        assertThat(factory.create(ServerConfig("s1", SourceType.EMBY, "Home", "http://nas:8096"))).isNull()
        // 有令牌但无 userId
        factory.tokenStore.put("s2", "TK")
        assertThat(factory.create(ServerConfig("s2", SourceType.EMBY, "Home", "http://nas:8096"))).isNull()
    }

    @Test fun `飞牛影视只需令牌不需要 userId`() {
        factory.tokenStore.put("f1", "FNTOKEN")
        // userId 留空也要能造出来（飞牛接口不依赖 userId）
        val source = factory.create(
            ServerConfig("f1", SourceType.FNOS, "飞牛影视", "http://192.168.31.222:5666"),
        )
        assertThat(source).isInstanceOf(FnSource::class.java)
        assertThat(source!!.type).isEqualTo(SourceType.FNOS)
        assertThat(source.serverId).isEqualTo("f1")

        // 但没有令牌仍然返回 null（需要重新登录）
        assertThat(
            factory.create(ServerConfig("f2", SourceType.FNOS, "飞牛影视", "http://192.168.31.222:5666"))
        ).isNull()
    }
}
