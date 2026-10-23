package app.myplayer.feature.server

import app.myplayer.core.datastore.InMemoryServerConfigStore
import app.myplayer.core.datastore.InMemoryTokenStore
import app.myplayer.core.model.SourceType
import app.myplayer.core.network.ApiException
import app.myplayer.source.httpapi.AuthResultDto
import app.myplayer.source.httpapi.UserDto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LoginViewModelTest {

    private fun vm(
        login: suspend (String, String) -> AuthResultDto,
        configs: InMemoryServerConfigStore = InMemoryServerConfigStore(),
        tokens: InMemoryTokenStore = InMemoryTokenStore(),
        displayName: String? = null,
    ) = LoginViewModel(
        serverId = "s1", sourceType = SourceType.EMBY, baseUrl = "http://nas:8096",
        login = login, configStore = configs, tokenStore = tokens, displayName = displayName,
    )

    @Test fun `登录成功写入配置与令牌且顺序正确`() = runTest {
        val configs = InMemoryServerConfigStore()
        val tokens = InMemoryTokenStore()
        val vm = vm({ _, _ -> AuthResultDto("TK", UserDto("u1", "bob")) }, configs, tokens)
        vm.onUsernameChange("bob"); vm.onPasswordChange("pw"); vm.submit()

        assertThat(vm.state.value.status).isEqualTo(LoginStatus.SUCCESS)
        val saved = configs.observeAll().first().single()
        assertThat(saved.userId).isEqualTo("u1")          // 必须早于构造 MediaSource，否则 requireNotNull(userId) 会炸
        assertThat(saved.baseUrl).isEqualTo("http://nas:8096")
        assertThat(tokens.get("s1")).isEqualTo("TK")
    }

    @Test fun `认证失败提示账号或密码错误`() = runTest {
        val vm = vm({ _, _ -> throw ApiException.Unauthorized() })
        vm.onUsernameChange("bob"); vm.onPasswordChange("bad"); vm.submit()
        assertThat(vm.state.value.status).isEqualTo(LoginStatus.ERROR)
        assertThat(vm.state.value.message).contains("账号或密码")
    }

    @Test fun `404 与网络异常给出不同文案`() = runTest {
        val notFound = vm({ _, _ -> throw ApiException.NotFound() })
        notFound.onUsernameChange("bob"); notFound.onPasswordChange("pw"); notFound.submit()
        assertThat(notFound.state.value.message).contains("404")

        val offline = vm({ _, _ -> throw java.net.ConnectException("refused") })
        offline.onUsernameChange("bob"); offline.onPasswordChange("pw"); offline.submit()
        assertThat(offline.state.value.message).contains("不可达")
    }

    @Test fun `空账号或密码不发起请求`() = runTest {
        var called = false
        val vm = vm({ _, _ -> called = true; AuthResultDto("T", UserDto("u", null)) })
        vm.submit()
        assertThat(called).isFalse()
        assertThat(vm.state.value.message).contains("请填写")
    }

    @Test fun `自定义名称优先于服务端账号名`() = runTest {
        val configs = InMemoryServerConfigStore()
        val vm = vm(
            { _, _ -> AuthResultDto("TK", UserDto("u1", "bob")) },
            configs,
            displayName = "客厅 Emby",
        )
        vm.onUsernameChange("bob"); vm.onPasswordChange("pw"); vm.submit()
        assertThat(configs.observeAll().first().single().name).isEqualTo("客厅 Emby")
    }

    @Test fun `名称为空白时回落到服务端账号名`() = runTest {
        val configs = InMemoryServerConfigStore()
        val vm = vm({ _, _ -> AuthResultDto("TK", UserDto("u1", "bob")) }, configs, displayName = "  ")
        vm.onUsernameChange("bob"); vm.onPasswordChange("pw"); vm.submit()
        assertThat(configs.observeAll().first().single().name).isEqualTo("bob")
    }
}
