package app.myplayer.source.local

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * SAF 持久化授权的读写（`docs/12-本机视频播放方案.md` §4.1）。
 *
 * 多根授权下，UI 需要逐根判断「是否仍然有效」（用户可能在系统设置里撤销某一根），
 * 所以这里只做**单根粒度**的检查与释放，列表本身由 `LocalRootStore` 管。
 */

/** 该 tree URI 的持久化读授权是否仍然有效。 */
fun isTreeUriGranted(context: Context, treeUri: String): Boolean = runCatching {
    context.contentResolver.persistedUriPermissions.any {
        it.isReadPermission && it.uri.toString() == treeUri
    }
}.getOrDefault(false)

/** 记住 SAF 授权（跨进程重启有效）。重复调用幂等。 */
fun persistTreeUriPermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}

/** 用户移除某根时释放授权，别让系统里留一堆用不到的 persisted permission。 */
fun releaseTreeUriPermission(context: Context, treeUri: String) {
    runCatching {
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(treeUri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}
