package app.myplayer.core.model

/**
 * 本机视频「多根授权」的纯函数工具（`docs/12-本机视频播放方案.md` §4.1）。
 *
 * 放在 `:core:model` 是因为**数据层**（`LocalRootStore` 需要展示名）和
 * **源层**（`:source:local` 需要路径前缀）都得用同一套解析：两份实现一旦漂移，
 * 就会出现「存在库里的路径前缀」和「扫描时拼出来的前缀」不一致 → 断点全部对不上。
 */

/**
 * 由 SAF tree URI 推出**稳定别名**（合并多根时的路径前缀）。
 *
 * tree URI 形如 `content://com.android.externalstorage.documents/tree/primary%3AMovies`，
 * 末段解码后是 `primary:Movies`；去掉卷前缀（`primary:` / `home:` / `<UUID>:`）即：
 * - `primary:Movies` → `Movies`
 * - `primary:Download/video` → `Download/video`
 * - `primary:`（内部存储根）→ ``（空前缀，条目路径就是树内相对路径）
 *
 * 别名只依赖授权时选中的目录本身，**不随显示名/排序变化**，所以可以安全地当作
 * 持久化的路径前缀（`file_media_items.path` / `playback_progress.mediaKey`）。
 */
fun treeAliasOf(treeUri: String): String {
    val raw = treeUri.substringAfterLast("/tree/", "")
    if (raw.isBlank()) return ""
    val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    val docId = decoded.removeSuffix("/")
    // documentId 的卷前缀：`primary:`、`home:`、`<UUID>:`（外部 SD 卡）等；也可能没有冒号
    val volume = docId.substringBefore(':', "")
    val rest = docId.substringAfter(':', docId).trim('/')
    // 内部存储（primary）不带卷前缀，路径好看且与历史数据一致；
    // 其它卷（home / SD 卡 UUID）必须保留，否则 `primary:Movies` 与 `home:Movies` 会撞成同一个前缀。
    return when {
        volume.isEmpty() || volume == "primary" -> rest
        rest.isEmpty() -> volume
        else -> "$volume:$rest"
    }
}

/** 展示名：别名末段（`Download/video` → `video`）；卷根（别名空）显示「本机存储」。 */
fun treeDisplayNameOf(treeUri: String): String = displayNameOfAlias(treeAliasOf(treeUri))

/** 别名 → 展示名（末段；空别名 = 卷根）。 */
fun displayNameOfAlias(alias: String): String =
    alias.substringAfterLast(':').substringAfterLast('/').ifBlank { "本机存储" }

/**
 * 两个根别名是否**互相覆盖**（同一条 / 祖先与后代）。
 *
 * 多根合并靠「路径前缀」区分来源，若同时保留 `Download` 和 `Download/video`，
 * 同一文件会出现两种前缀写法（`Download/video/x.mp4` 与 `Download/video/x.mp4` 恰好撞车，
 * 或 `Download/x.mp4` 归属二义）→ 进度/历史串号。所以写入时同族根只保留**最宽**的一根。
 *
 * 空别名 = 卷根，包含该卷内一切。
 */
fun aliasCovers(a: String, b: String): Boolean {
    if (a == b) return true
    if (a.isEmpty() || b.isEmpty()) return true
    return b.startsWith("$a/") || a.startsWith("$b/")
}
