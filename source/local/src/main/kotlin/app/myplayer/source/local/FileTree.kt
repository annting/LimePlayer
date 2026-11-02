package app.myplayer.source.local

import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * 「全盘访问」档的目录树（`docs/12-本机视频播放方案.md` §3.1）：直接用 `java.io.File` 读。
 *
 * 前提：用户在系统设置里开了「所有文件访问权限」（`MANAGE_EXTERNAL_STORAGE`）；
 * 没有该权限时外部存储对 `File` 几乎是空的，所以**调用方必须先判权限**再用本类。
 *
 * 与 [SafTree] 的差异：
 * - 根 = `/storage/emulated/0`，[alias] 为空串 → 路径形如 `Movies/沙丘.mkv`，
 *   与"把内部存储根授权给 SAF"时的路径口径一致（但系统根本不让选根，所以 SAF 档实际是 `Movies/...` 这种子根）；
 * - 播放 URI = `file://`（ExoPlayer 原生支持）；
 * - 覆盖范围比 SAF 大：**能进 `Download` 根**（SAF 明确禁止选它）；
 * - 仍然进不了 `Android/data`、`Android/obb`（系统级封锁，全盘权限也不例外）。
 */
internal class FileTree(
    private val base: File,
    override val alias: String = "",
) : LocalTree {

    override fun children(relPath: String): List<LocalNode> {
        val dir = if (relPath.isBlank()) base else File(base, relPath)
        if (!dir.isDirectory) return emptyList()
        return runCatching {
            dir.listFiles()?.mapNotNull { it.toNode() }.orEmpty()
        }.getOrDefault(emptyList())
    }

    override fun resolve(relPath: String): LocalNode? {
        if (relPath.isBlank()) return base.toNode()
        return runCatching { File(base, relPath).takeIf { it.exists() }?.toNode() }.getOrNull()
    }

    override fun uriOf(node: LocalNode): Uri = Uri.fromFile(File(node.documentId))

    override fun delete(node: LocalNode): Boolean =
        runCatching { File(node.documentId).delete() }.getOrDefault(false)

    private fun File.toNode(): LocalNode? = runCatching {
        LocalNode(
            // 全盘档没有 documentId，用绝对路径当句柄（不落库，仅当次使用）
            documentId = absolutePath,
            name = name,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else length(),
            lastModified = lastModified(),
            mimeType = null,
        )
    }.getOrNull()

    companion object {
        /** 外部存储根（`/storage/emulated/0`）；不可用时返回 null。 */
        @Suppress("DEPRECATION")
        fun external(): File? {
            val root = Environment.getExternalStorageDirectory() ?: return null
            return root.takeIf { it.isDirectory }
        }
    }
}
