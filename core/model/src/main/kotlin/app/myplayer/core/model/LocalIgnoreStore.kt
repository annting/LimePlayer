package app.myplayer.core.model

/**
 * 本机视频源的「忽略文件夹」偏好（`docs/12-本机视频播放方案.md` §4.6）。
 *
 * 语义：扫描列出来的「有视频的文件夹」可以逐个标记为忽略 —— 被忽略的文件夹不进媒体库网格，
 * 但其文件仍保留在 [FileCatalogStore]（**不删数据，只隐藏**）。
 *
 * 与档二隐私（[FileCatalogStore.privatePaths]）**相互独立**：
 * - 隐私 = 隐私模式开关驱动的隐藏（`docs/隐私模式方案.md`）；
 * - 忽略 = 用户逐文件夹的普通偏好，任何模式下都生效。
 *
 * 实现方：`:core:database`（Room）；`:source:local` 只依赖本接口，不依赖 Room。
 */
interface LocalIgnoreStore {
    /** 当前被忽略的文件夹（相对源根，`/` 分隔；空集合 = 没有忽略任何文件夹）。 */
    suspend fun ignoredFolders(sourceId: String): Set<String>

    /** 忽略一个文件夹（幂等：重复写入不报错）。 */
    suspend fun ignore(sourceId: String, folderPath: String)

    /** 恢复一个被忽略的文件夹（不存在时静默）。 */
    suspend fun restore(sourceId: String, folderPath: String)

    /** 清空某源的全部忽略项（删除本机源时用）。 */
    suspend fun clear(sourceId: String): Unit = Unit
}

/** 测试 / 预览用的空实现：不忽略任何文件夹。 */
object NoIgnoredFolders : LocalIgnoreStore {
    override suspend fun ignoredFolders(sourceId: String): Set<String> = emptySet()
    override suspend fun ignore(sourceId: String, folderPath: String) = Unit
    override suspend fun restore(sourceId: String, folderPath: String) = Unit
}
