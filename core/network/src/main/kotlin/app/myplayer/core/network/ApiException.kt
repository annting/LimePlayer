package app.myplayer.core.network

/** HTTP 状态码 → 领域异常（映射规则见 docs/02-技术规范.md §8.1）。 */
sealed class ApiException(message: String) : Exception(message) {
    class Unauthorized(message: String = "认证失败") : ApiException(message)
    class NotFound(message: String = "资源不存在") : ApiException(message)
    class ServerError(val code: Int, val body: String) : ApiException("服务端错误 $code")
    class Unknown(val code: Int, val body: String) : ApiException("未知错误 $code")

    companion object {
        fun fromStatus(code: Int, body: String): ApiException = when {
            code == 401 || code == 403 -> Unauthorized()
            code == 404 -> NotFound()
            code in 500..599 -> ServerError(code, body)
            else -> Unknown(code, body)
        }
    }
}
