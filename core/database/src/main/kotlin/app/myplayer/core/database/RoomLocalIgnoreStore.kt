package app.myplayer.core.database

import app.myplayer.core.model.LocalIgnoreStore

/**
 * [LocalIgnoreStore] 的 Room 实现（`docs/12-本机视频播放方案.md` §4.6）。
 *
 * 只存「用户忽略的文件夹路径」这一层偏好，不参与隐私策略（那份过滤在 [RoomFileCatalog]）。
 * 因此这里不需要 `PrivacyVisibilityPolicy` —— 忽略列表本身就是用户主动书写的可见配置。
 */
class RoomLocalIgnoreStore(private val db: MediaDatabase) : LocalIgnoreStore {

    private val dao get() = db.localIgnoreDao()

    override suspend fun ignoredFolders(sourceId: String): Set<String> =
        dao.folders(sourceId).toSet()

    override suspend fun ignore(sourceId: String, folderPath: String) {
        dao.insert(LocalIgnoredFolderEntity(sourceId, folderPath, System.currentTimeMillis()))
    }

    override suspend fun restore(sourceId: String, folderPath: String) {
        dao.delete(sourceId, folderPath)
    }

    override suspend fun clear(sourceId: String) {
        dao.clear(sourceId)
    }
}
