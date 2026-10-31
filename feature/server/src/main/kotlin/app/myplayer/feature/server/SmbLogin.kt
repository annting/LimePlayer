package app.myplayer.feature.server

import app.myplayer.source.smb.SmbClient
import app.myplayer.source.smb.SmbConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * SMB 的「登录凭据」。
 *
 * SMB 没有令牌交换，账号密码本身就是每次连接要用的凭据，所以存进 [app.myplayer.core.datastore.TokenStore]
 * （底层是 Keystore 加密的 `token_<id>.bin`），与其它源的 token 走同一条加密链路。
 *
 * 序列化格式：`b64(用户名)|b64(密码)|b64(域)` —— 三个字段各自 Base64，
 * 避免密码里出现 `|` / `\n` 把记录撑坏；不用 JSON 是为了 feature:server 不引入序列化依赖。
 */
data class SmbCredentials(
    val username: String,
    val password: String,
    val domain: String = "",
) {
    fun encode(): String = listOf(username, password, domain)
        .joinToString("|") { Base64.getEncoder().encodeToString(it.toByteArray()) }

    companion object {
        fun decode(raw: String?): SmbCredentials? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('|')
            if (parts.size != 3) return null
            return runCatching {
                SmbCredentials(
                    username = String(Base64.getDecoder().decode(parts[0])),
                    password = String(Base64.getDecoder().decode(parts[1])),
                    domain = String(Base64.getDecoder().decode(parts[2])),
                )
            }.getOrNull()
        }
    }
}

/**
 * 保存服务器时的**真实连通性校验**：用给定凭据列一次共享根。
 *
 * 不做「先存后验」——凭据错了就当场报错，不要留下一个连不上的 SMB 配置。
 *
 * @return 形如 `SMB 192.168.31.15/video · 6 项` 的成功描述
 * @throws IllegalStateException 连不上 / 共享不存在 / 认证失败（message 直接给用户看）
 */
suspend fun testSmbConnection(config: SmbConfig): String = withContext(Dispatchers.IO) {
    try {
        val info = SmbClient(config).testConnection()
        // 成功也顺手记进连接池，紧接着的首页加载不必再协商一次
        app.myplayer.source.smb.SmbClients.of(config)
        info
    } catch (e: Exception) {
        val reason = e.message ?: e::class.java.simpleName
        throw IllegalStateException("连接 SMB 失败：$reason", e)
    }
}
