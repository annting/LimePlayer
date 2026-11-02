package app.myplayer.source.local

import android.net.Uri

/**
 * 「一棵本机目录树」的抽象（`docs/12-本机视频播放方案.md` §3.1）。
 *
 * 本机视频有两条取文件通道，**对外只暴露这一层**，上层（`LocalMediaSource`）不关心
 * 底下是 SAF 还是全盘文件系统：
 *
 * | 实现 | 前提 | 访问方式 | 播放 URI |
 * |---|---|---|---|
 * | [SafTree] | 用户在系统选择器里选过目录 | `DocumentsContract` + `ContentResolver` | `content://…` |
 * | [FileTree] | 用户开了「所有文件访问权限」 | `java.io.File` 直读 | `file://…` |
 *
 * 两者**共用同一套路径语义**：`alias`（路径前缀）+ 树内相对路径。
 * 全盘档的 alias 是空串（= 外部存储根），所以 `Movies/a.mp4` 在两档下的
 * `mediaKey` 完全一致 → 换档不丢进度。
 */
internal interface LocalTree {
    /** 路径前缀别名；空串 = 整块外部存储（全盘档）或卷根。 */
    val alias: String

    /** 列 [relPath]（空串 = 根）下的直接子节点；失败返回空表，不抛。 */
    fun children(relPath: String): List<LocalNode>

    /** 按树内相对路径解析节点；不存在返回 null。 */
    fun resolve(relPath: String): LocalNode?

    /** 播放地址：SAF = `content://`，全盘 = `file://`。 */
    fun uriOf(node: LocalNode): Uri

    /** 物理删除。不支持 / 失败返回 false。 */
    fun delete(node: LocalNode): Boolean
}

/**
 * 树里的一个节点。
 *
 * [documentId] 是**通道相关的不透明句柄**：SAF 下是 documentId，全盘档下是文件绝对路径。
 * 它**不落库**（落库的是相对路径），每次按路径现取。
 */
internal data class LocalNode(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val mimeType: String? = null,
)
