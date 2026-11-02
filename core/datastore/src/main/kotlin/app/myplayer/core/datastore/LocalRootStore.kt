package app.myplayer.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.myplayer.core.model.aliasCovers
import app.myplayer.core.model.treeAliasOf
import app.myplayer.core.model.treeDisplayNameOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 已授权的一根本机视频目录（`docs/12-本机视频播放方案.md` §4.1）。
 *
 * [treeUri] 是 SAF 的 tree URI（`content://com.android.externalstorage.documents/tree/...`），
 * 授权成功时调用 `takePersistableUriPermission` 后才可跨进程重启使用。
 *
 * **多根**：Android 11+ 禁止选择内部存储根，所以"整台手机"实际上只能通过
 * 授权多棵子树（Movies + Download + …）拼出来。列表为空 = 未授权。
 */
data class LocalRoot(
    val treeUri: String,
    /** 展示名（由 tree URI 的 documentId 末段推出，如 `Movies`；卷根显示「本机存储」）。 */
    val displayName: String,
) {
    /**
     * 合并库里的**路径前缀**（稳定别名）。
     *
     * 多根合并后，条目路径必须能区分来自哪一根（否则 `Movies/沙丘.mkv` 在两棵树下会撞车，
     * 进度/历史会串）。取 tree documentId 去掉卷前缀的部分：
     * - `primary:Movies` → `Movies`
     * - `primary:Download/video` → `Download/video`
     * - `primary:`（内部存储根）→ `` （空前缀，路径就是树内相对路径）
     */
    val alias: String get() = treeAliasOf(treeUri)
}

/**
 * 本机根目录的持久化（**多根**）。
 *
 * **独立于 [ServerConfigStore]**：本机不是"服务器"，不参与服务器列表/切换器；
 * 用一份单独的 Preferences DataStore 存 tree URI 列表，避免把本机塞进服务器枚举后
 * 到处都要写「排除 LOCAL」的过滤。
 *
 * 风险点（`docs/12` §3）：tree URI 若只存内存，App 重启即失效。所以必须落盘，
 * 授权被撤销时由调用方把该根标为失效并引导重新授权，而不是误删条目。
 */
interface LocalRootStore {
    /** 空列表 = 未授权（本机 tab 显示授权引导）。 */
    fun observe(): Flow<List<LocalRoot>>
    /**
     * 追加一根；已存在则只更新（同一目录重复授权不该出现两行）。
     *
     * @return **根列表是否真的变了**。false = 这次授权没带来新目录（同一条，或已被更宽的根覆盖），
     * UI 据此给"已在授权范围内"的提示，而不是静默无反应。
     */
    suspend fun add(treeUri: String): Boolean
    suspend fun remove(treeUri: String)
    suspend fun clear()
}

class DataStoreLocalRootStore(
    private val dataStore: DataStore<Preferences>,
) : LocalRootStore {

    override fun observe(): Flow<List<LocalRoot>> = dataStore.data.map { prefs -> rootsOf(prefs) }

    override suspend fun add(treeUri: String): Boolean {
        if (treeUri.isBlank()) return false
        var changed = false
        dataStore.edit { prefs ->
            val existing = rootsOf(prefs).map { it.treeUri }
            val newAlias = treeAliasOf(treeUri)
            // 同族根（祖先 / 后代 / 同一条）只保留最宽的一根：
            // 否则合并视图里同一文件会有两种路径前缀，进度与历史会串号。
            val kept = existing.filterNot { aliasCovers(newAlias, treeAliasOf(it)) }
            val coveredByKept = kept.any { aliasCovers(treeAliasOf(it), newAlias) }
            val next = if (coveredByKept) kept else kept + treeUri
            changed = next != existing
            prefs.writeRoots(next)
        }
        return changed
    }

    override suspend fun remove(treeUri: String) {
        dataStore.edit { prefs ->
            prefs.writeRoots(rootsOf(prefs).map { it.treeUri }.filterNot { it == treeUri })
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_TREE_URIS)
            prefs.remove(KEY_LEGACY_TREE_URI)
            prefs.remove(KEY_LEGACY_DISPLAY_NAME)
        }
    }

    /** 读列表；顺带兼容旧版"单根"两个 key（老用户升级后不丢授权）。 */
    private fun rootsOf(prefs: Preferences): List<LocalRoot> {
        val stored = prefs[KEY_TREE_URIS]
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val uris = if (stored.isNotEmpty()) {
            stored
        } else {
            listOfNotNull(prefs[KEY_LEGACY_TREE_URI]?.takeIf { it.isNotBlank() })
        }
        return uris.distinct().map { LocalRoot(treeUri = it, displayName = treeDisplayNameOf(it)) }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeRoots(uris: List<String>) {
        val clean = uris.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) {
            remove(KEY_TREE_URIS)
        } else {
            // 换行分隔（tree URI 不含换行），而不是 stringSet：要保住用户添加顺序
            this[KEY_TREE_URIS] = clean.joinToString("\n")
        }
        // 旧 key 一律清掉，避免下次读时把已移除的单根又"复活"
        remove(KEY_LEGACY_TREE_URI)
        remove(KEY_LEGACY_DISPLAY_NAME)
    }

    private companion object {
        /** 多根（换行分隔的 tree URI 列表）。 */
        val KEY_TREE_URIS = stringPreferencesKey("local_root_tree_uris")

        /** v1 单根遗留 key（仅读，写入时清除）。 */
        val KEY_LEGACY_TREE_URI = stringPreferencesKey("local_root_tree_uri")
        val KEY_LEGACY_DISPLAY_NAME = stringPreferencesKey("local_root_display_name")
    }
}
