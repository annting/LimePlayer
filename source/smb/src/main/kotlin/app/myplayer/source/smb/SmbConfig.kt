package app.myplayer.source.smb

/**
 * 一台 SMB 服务器（= 一个共享目录）的连接参数。
 *
 * 约定：**一个 SMB 服务器配置 = 一个共享目录**（如 `\\192.168.31.15\video`）。
 * 共享目录下的每个顶层文件夹被当作一个「库」（首页「我的文件夹」），
 * 这样与「切换媒体库」的交互保持一致，也不必做跨共享的聚合。
 *
 * @param host 主机名或 IP（不带 `smb://`）
 * @param port 默认 445
 * @param share 共享名，如 `video`（不带斜杠）
 * @param domain NT 域 / 工作组，家庭 NAS 通常留空
 */
data class SmbConfig(
    val host: String,
    val port: Int = 445,
    val share: String,
    val domain: String = "",
    val username: String = "",
    val password: String = "",
) {
    /** jcifs-ng 的根地址：`smb://host:port/share`。 */
    val baseUrl: String
        get() = "smb://$host:$port/${share.trim('/')}"

    /** 拼某个相对路径的完整 URL（`path` 为空 = 共享根）。 */
    fun urlFor(path: String): String {
        val clean = path.trim('/')
        return if (clean.isEmpty()) baseUrl else "$baseUrl/$clean"
    }

    companion object {
        /**
         * 从落库的 `baseUrl` 解析出配置（`ServerConfig.baseUrl` 存的就是 `smb://host:445/share`）。
         *
         * 容错 `\\host\share`、`//host/share`、`host/share` 三种写法 —— 用户很可能直接
         * 把 Windows 资源管理器地址栏里那串粘进来。
         */
        fun parse(
            rawUrl: String,
            domain: String = "",
            username: String = "",
            password: String = "",
        ): SmbConfig? {
            val text = rawUrl.trim()
                .removePrefix("smb://").removePrefix("SMB://")
                .replace('\\', '/')
                .trim('/')
            if (text.isEmpty()) return null
            val authority = text.substringBefore('/')
            val sharePath = text.substringAfter('/', "").trim('/')
            val host = authority.substringBeforeLast(':', authority)
            val port = authority.substringAfterLast(':', "").toIntOrNull() ?: 445
            if (host.isEmpty() || sharePath.isEmpty()) return null
            // 共享名只取第一段（`\\nas\video\movies` 这类多级输入只认 `video`）
            val share = sharePath.substringBefore('/')
            return SmbConfig(
                host = host,
                port = port,
                share = share,
                domain = domain,
                username = username,
                password = password,
            )
        }
    }
}

/** 共享里的一个条目（文件或目录）。 */
data class SmbEntry(
    /** 相对共享根的路径（不含首尾斜杠），如 `Movies/沙丘.mkv`。 */
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

/** 认作「视频」的扩展名（与 Emby/飞牛的容器口径对齐；其余文件直接忽略）。 */
internal val VideoExtensions = setOf(
    "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "webm", "ts", "m2ts", "mpg", "mpeg", "iso", "rmvb",
)

/** 认作「字幕」的扩展名（外挂字幕发现用，v1 只用于详情页展示）。 */
internal val SubtitleExtensions = setOf("srt", "ass", "ssa", "vtt", "sub")

internal fun String.extensionLower(): String = substringAfterLast('.', "").lowercase()

internal fun String.isVideoFile(): Boolean = extensionLower() in VideoExtensions
