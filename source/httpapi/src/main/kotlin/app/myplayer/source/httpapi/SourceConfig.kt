package app.myplayer.source.httpapi

data class SourceConfig(
    val serverId: String,
    val baseUrl: String,            // 已去除尾斜杠
    val prefix: String = "",        // 飞牛实测前缀（M0 结论），Emby 默认空
    val token: String? = null,
    val userId: String? = null,
    val deviceId: String = "unknown",
    val clientName: String = "myplayer",
    val clientVersion: String = "0.1.0",
    val authHeaderStyle: AuthHeaderStyle = AuthHeaderStyle.X_EMBY_TOKEN,
)

enum class AuthHeaderStyle { X_EMBY_TOKEN, MEDIABROWSER_AUTHORIZATION }
