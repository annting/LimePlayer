package app.myplayer.source.smb

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * jcifs-ng 的薄封装：只暴露「列目录 / 看属性 / 读字节」三件事。
 *
 * 全部方法都是**阻塞 IO**，调用方必须在 `Dispatchers.IO` 上跑（[SmbMediaSource] 负责）。
 * [CIFSContext] 内部持有连接池，所以这里做成进程级单例并缓存（见 [SmbClients]）——
 * 每次列目录都新建上下文会重新做一次 SMB 协商，慢且容易被 NAS 判定为异常登录。
 */
class SmbClient(val config: SmbConfig) {

    private val context: CIFSContext by lazy {
        val props = Properties().apply {
            // 家庭 NAS 都在局域网：超时不能太长，否则「连不上」时界面要等半分钟
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.smb.client.soTimeout", "35000")
            // 允许 SMB1 回落：老式 NAS / 路由器硬盘盒可能只开 SMB1
            setProperty("jcifs.smb.client.minVersion", "SMB1")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
        }
        BaseContext(PropertyConfiguration(props)).withCredentials(
            NtlmPasswordAuthenticator(
                config.domain.ifBlank { null },
                config.username.ifBlank { null },
                config.password.ifBlank { null },
            ),
        )
    }

    /** 连接 + 列共享根，用于「保存服务器」时的真实连通性校验。 */
    fun testConnection(): String {
        val root = SmbFile(config.baseUrl + "/", context)
        if (!root.exists()) error("共享目录不存在或无权限：${config.baseUrl}")
        val children = root.listFiles()
        return "SMB ${config.host}/${config.share} · ${children.size} 项"
    }

    /**
     * 列目录（不递归）。[path] 是相对共享根的路径，空串 = 共享根。
     *
     * 过滤：`$` 开头的系统目录（`$RECYCLE.BIN`、`@eaDir` 之类）与隐藏文件。
     */
    fun list(path: String): List<SmbEntry> {
        val dir = SmbFile(config.urlFor(path) + "/", context)
        if (!dir.exists()) return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        return children.mapNotNull { child ->
            val name = child.name.trimEnd('/')
            if (name.isEmpty() || name.startsWith("$") || name.startsWith(".") || name.startsWith("@")) {
                null
            } else {
                SmbEntry(
                    path = listOf(path.trim('/'), name).filter { it.isNotEmpty() }.joinToString("/"),
                    name = name,
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isDirectory) 0L else runCatching { child.length() }.getOrDefault(0L),
                    lastModifiedMillis = runCatching { child.lastModified() }.getOrDefault(0L),
                )
            }
        }
            // 目录在前、同类按名称（中文按拼音序由 Collator 兜底，这里用不区分大小写比较即可）
            .sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** 文件属性；不存在返回 null。 */
    fun stat(path: String): SmbEntry? {
        if (path.isBlank()) return null
        val file = SmbFile(config.urlFor(path), context)
        if (!file.exists()) return null
        val name = file.name.trimEnd('/')
        return SmbEntry(
            path = path.trim('/'),
            name = name,
            isDirectory = file.isDirectory,
            sizeBytes = if (file.isDirectory) 0L else runCatching { file.length() }.getOrDefault(0L),
            lastModifiedMillis = runCatching { file.lastModified() }.getOrDefault(0L),
        )
    }

    /** 打开只读随机访问句柄（HTTP Range 靠它做真 seek，而不是把前面的字节读掉）。 */
    fun openRandom(path: String): SmbRandomAccessFile =
        // 2 参构造器内部按「读共享」打开，不需要自己拼 sharing 位
        SmbRandomAccessFile(SmbFile(config.urlFor(path), context), "r")

    /** 共享总字节数（首页/详情页统计用；失败返回 0）。 */
    fun totalBytes(path: String = ""): Long =
        runCatching { list(path).filterNot { it.isDirectory }.sumOf { it.sizeBytes } }.getOrDefault(0L)
}

/**
 * 进程内复用 [SmbClient]：同一份配置（host/share/user）只建一次上下文。
 * 改密码 / 换服务器会生成新 key，旧上下文靠 LRU 上限淘汰。
 */
object SmbClients {
    private const val MAX_CLIENTS = 4
    private val cache = ConcurrentHashMap<String, SmbClient>()

    fun of(config: SmbConfig): SmbClient {
        val key = "${config.host}:${config.port}/${config.share}|${config.domain}\\${config.username}"
        cache[key]?.let { return it }
        if (cache.size >= MAX_CLIENTS) cache.keys.firstOrNull()?.let(cache::remove)
        return SmbClient(config).also { cache[key] = it }
    }

    /** 退出登录 / 删除服务器时清掉连接（避免旧凭据被复用）。 */
    fun evict(config: SmbConfig) {
        val key = "${config.host}:${config.port}/${config.share}|${config.domain}\\${config.username}"
        cache.remove(key)
    }
}
