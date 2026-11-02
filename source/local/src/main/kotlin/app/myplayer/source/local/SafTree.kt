package app.myplayer.source.local

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF（Storage Access Framework）目录树的薄封装（`docs/12-本机视频播放方案.md` §3）。
 *
 * 只做：列子节点、按相对路径解析节点、拿节点的 document URI（播放用）、删除。
 * 用 `DocumentsContract` + `ContentResolver` 而不是 `DocumentFile`：少一个依赖，
 * 且 Android 11+ 的分区存储下 `DocumentFile` 底层也是这几个 API。
 *
 * 路径口径：相对源根的**显示名拼接**（如 `Movies/沙丘.mkv`），与 `FileSeen.path` 一致。
 */
internal class SafTree(
    private val resolver: ContentResolver,
    val treeUri: Uri,
    override val alias: String,
) : LocalTree {

    val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override fun children(relPath: String): List<LocalNode> {
        val parentId = if (relPath.isBlank()) {
            rootDocumentId
        } else {
            resolve(relPath)?.documentId ?: return emptyList()
        }
        return listChildren(parentId)
    }

    /** 该文档的直接子节点（documentId 入参版本）。 */
    fun listChildren(parentDocumentId: String): List<LocalNode> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<LocalNode>()
        runCatching {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val timeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (c.moveToNext()) {
                    val mime = c.getString(mimeIdx)
                    val name = c.getString(nameIdx) ?: ""
                    val size = if (c.isNull(sizeIdx)) 0L else c.getLong(sizeIdx)
                    out += LocalNode(
                        documentId = c.getString(idIdx),
                        name = name,
                        // 目录判定有个兜底（2026-11-01 ColorOS 反馈：DCIM 下全是"文件"，扫不出库）：
                        // 部分 OEM 的 DocumentsProvider 不给目录下发 `MIME_TYPE_DIR`（返回 null/空），
                        // 只按 MIME 判会把**所有目录**当成普通文件跳过 → 整棵树扫出 0 个库。
                        // 兜底条件刻意收紧：mime 缺失 + 大小 0 + 名字没有扩展名，三者同时成立才当目录。
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR ||
                            (mime.isNullOrBlank() && size == 0L && name.extensionLower().isEmpty()),
                        sizeBytes = size,
                        lastModified = if (c.isNull(timeIdx)) 0L else c.getLong(timeIdx),
                        mimeType = mime,
                    )
                }
            }
        }.onFailure {
            // 不要静默：授权失效 / provider 异常时，上层只会看到「没有视频」，日志是唯一线索
            android.util.Log.w("LimeLocal", "列目录失败：$uri", it)
        }
        return out
    }

    /** 相对路径 → 节点；逐段按显示名匹配，找不到返回 null。 */
    override fun resolve(relPath: String): LocalNode? {
        if (relPath.isBlank()) return null
        var parent = rootDocumentId
        var node: LocalNode? = null
        for (segment in relPath.split('/')) {
            if (segment.isBlank()) return null
            val child = listChildren(parent).firstOrNull { it.name == segment } ?: return null
            parent = child.documentId
            node = child
        }
        return node
    }

    override fun uriOf(node: LocalNode): Uri = documentUri(node.documentId)

    fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    override fun delete(node: LocalNode): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, documentUri(node.documentId)) }
            .getOrDefault(false)
}
