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
    /**
     * 用户自定义的服务器显示名（「修改服务器」里填的）。
     * 非空时优先于服务端返回的账号名 —— 否则登录一过，自定义名称就被覆盖掉。
     */
    private val displayName: String? = null,
    /** 存为隐私源：敏感字段与令牌都走 DEK 加密区（见 [ServerConfig.isPrivate]）。 */
    private val isPrivate: Boolean = false,
) {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    fun onUsernameChange(v: String) { _state.value = _state.value.copy(username = v, message = null) }
    fun onPasswordChange(v: String) { _state.value = _state.value.copy(password = v, message = null) }

    suspend fun submit() {
        val s = _state.value
        // 只校验账号：Emby / Jellyfin 允许「无密码」账号（设置页的认证方式 = 无密码），
        // 空密码照样发给服务端，由服务端裁决，本地不做前置拦截。
        if (s.username.isBlank()) {
            _state.value = s.copy(status = LoginStatus.ERROR, message = "请填写账号")
            return
        }
        _state.value = s.copy(status = LoginStatus.SUBMITTING, message = null)
        try {
            val result = login(s.username, s.password)
            // 顺序是有意的：**先配置、后令牌**。
            // · 配置里带 userId，上层才能安全构造 MediaSource（取数接口 requireNotNull(userId)）；
            // · 私有源的令牌要写进加密区，而加密条目是随配置落盘才建立的 ——
            //   反过来写的话令牌会先进普通 `token_<id>.bin`，等于绕过加密区。
            configStore.upsert(
                ServerConfig(
                    id = serverId,
                    sourceType = sourceType,
                    // 自定义名（修改服务器）> 服务端账号名 > 地址兜底
                    name = displayName?.takeIf { it.isNotBlank() } ?: result.user.name ?: baseUrl,
                    baseUrl = baseUrl,
                    userId = result.user.id,
                    isPrivate = isPrivate,
                )
            )
            tokenStore.put(serverId, result.accessToken)
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
