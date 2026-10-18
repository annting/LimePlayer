package app.myplayer.feature.server

import app.myplayer.source.fnos.FnClient
import app.myplayer.source.httpapi.AuthResultDto
import app.myplayer.source.httpapi.UserDto

/**
 * 飞牛影视登录适配：把 `FnClient.login`（只返回 token）包装成 UI 层统一的 [AuthResultDto]。
 *
 * 飞牛的账号体系没有独立 userId，用账号名同时充当 userId（`ServerConfig.userId` 只用于展示，
 * `FnSource` 完全不需要它）。放在 `:feature:server` 而不是 `:app`，保证 app 层只依赖 feature、
 * 不直接依赖任何 `:source:*`（`docs/02` §3.2 的分层约定）。
 */
suspend fun fnosLogin(baseUrl: String, username: String, password: String): AuthResultDto {
    val token = FnClient(baseUrl = baseUrl).login(username, password)
    return AuthResultDto(accessToken = token, user = UserDto(id = username, name = username))
}
