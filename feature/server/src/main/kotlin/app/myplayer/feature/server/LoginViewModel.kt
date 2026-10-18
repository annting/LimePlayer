package app.myplayer.feature.server

import app.myplayer.core.datastore.ServerConfig
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.datastore.TokenStore
import app.myplayer.core.model.SourceType
import app.myplayer.core.network.ApiException
import app.myplayer.source.httpapi.AuthResultDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

enum class LoginStatus { IDLE, SUBMITTING, SUCCESS, ERROR }

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val status: LoginStatus = LoginStatus.IDLE,
    val message: String? = null,
)

class LoginViewModel(
    private val serverId: String,
    private val sourceType: SourceType,
    private val baseUrl: String,
    /** 生产环境传 `httpApi::authenticateByName`（飞牛传 `FnClient.login` 的适配）；测试传假实现。 */
    private val login: suspend (String, String) -> AuthResultDto,
    private val configStore: ServerConfigStore,
    private val tokenStore: TokenStore,
    /** 登录成功后把这个服务器设为当前服务器（多服务器切换）。测试可不传。 */
    private val settingsStore: app.myplayer.core.datastore.SettingsStore? = null,
) {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    fun onUsernameChange(v: String) { _state.value = _state.value.copy(username = v, message = null) }
    fun onPasswordChange(v: String) { _state.value = _state.value.copy(password = v, message = null) }

    suspend fun submit() {
        val s = _state.value
        if (s.username.isBlank() || s.password.isBlank()) {
            _state.value = s.copy(status = LoginStatus.ERROR, message = "请填写账号与密码")
            return
        }
        _state.value = s.copy(status = LoginStatus.SUBMITTING, message = null)
        try {
            val result = login(s.username, s.password)
            // 顺序是有意的：先落盘 userId 与令牌，上层才能安全构造 MediaSource（取数接口 requireNotNull(userId)）
            tokenStore.put(serverId, result.accessToken)
            configStore.upsert(
                ServerConfig(
                    id = serverId,
                    sourceType = sourceType,
                    name = result.user.name ?: baseUrl,
                    baseUrl = baseUrl,
                    userId = result.user.id,
                )
            )
            // 新登录的服务器即当前服务器：否则「添加第二台服务器」后仍停在旧服务器上，
            // 用户会以为添加失败（见 M5 服务器管理）。
            settingsStore?.setActiveServerId(serverId)
            _state.value = _state.value.copy(status = LoginStatus.SUCCESS)
        } catch (e: ApiException.Unauthorized) {
            _state.value = _state.value.copy(status = LoginStatus.ERROR, message = "账号或密码错误")
        } catch (e: ApiException.NotFound) {
            _state.value = _state.value.copy(status = LoginStatus.ERROR, message = "服务器响应 404：请检查端口与路径前缀")
        } catch (e: IOException) {
            _state.value = _state.value.copy(status = LoginStatus.ERROR, message = "服务器地址不可达，请检查网络与地址")
        } catch (e: Exception) {
            _state.value = _state.value.copy(status = LoginStatus.ERROR, message = "登录失败：${e.message}")
        }
    }
}
