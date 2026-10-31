package app.myplayer.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Upsert suspend fun upsertAll(rows: List<LibraryEntity>)

    @Query("SELECT * FROM libraries WHERE serverId = :serverId ORDER BY name")
    fun observe(serverId: String): Flow<List<LibraryEntity>>

    @Query("DELETE FROM libraries WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)
}

@Dao
interface MediaItemDao {
    @Upsert suspend fun upsertAll(rows: List<MediaItemEntity>)

    @Query("SELECT * FROM media_items WHERE serverId = :serverId ORDER BY title")
    fun observeAll(serverId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE serverId = :serverId AND libraryId = :libraryId ORDER BY sortTitle")
    fun observeLibrary(serverId: String, libraryId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT COUNT(*) FROM media_items WHERE serverId = :serverId AND libraryId = :libraryId")
    suspend fun count(serverId: String, libraryId: String): Int

    @Query("DELETE FROM media_items WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)

    // ---- 详情页写操作写回（`02` §5.1，详情页二期）----

    @Query("SELECT * FROM media_items WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun get(serverId: String, itemId: String): MediaItemEntity?

    /** 只更新观看状态三字段（服务端权威 UserData 写回），不触碰其余列。 */
    @Query(
        "UPDATE media_items SET isPlayed = :isPlayed, resumePositionTicks = :resumePositionTicks, " +
            "isFavorite = :isFavorite WHERE serverId = :serverId AND itemId = :itemId"
    )
    suspend fun updateUserData(
        serverId: String,
        itemId: String,
        isPlayed: Boolean,
        resumePositionTicks: Long,
        isFavorite: Boolean,
    )

    /** 单条删除（服务端删除成功后清理本地缓存）。 */
    @Query("DELETE FROM media_items WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun delete(serverId: String, itemId: String)
}

/**
 * 文件型条目的库存读写（`docs/10-数据存储方案.md` §6.3）。
 *
 * 两条写规则是硬约束：
 * 1. 新增走 `INSERT OR IGNORE` —— 保证 `firstSeenAt` 只写一次（@Upsert 会覆盖它，F3）；
 * 2. 已存在的行走 [touch] 的白名单 UPDATE —— 白名单里**没有** `firstSeenAt`，也没有 probe 字段。
 *
 * **隐私过滤**：所有**读**查询都带 `includePrivate`（`docs/隐私模式方案.md` §三 P0#2）。
 * 由 `RoomFileCatalog` 从 `PrivacyVisibilityPolicy` 取值传入，UI/源层**无法绕过** ——
 * 它们拿到的接口里没有这个参数。写查询一律不带：扫描/探测/标隐私与「现在能不能看」无关。
 */
@Dao
interface FileMediaItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<FileMediaItemEntity>)

    /** 重扫命中：只刷新「会变」的列，`firstSeenAt` 与 probe 结果一律不动。 */
    @Query(
        "UPDATE file_media_items SET name = :name, title = :title, sortTitle = :sortTitle, " +
            "sizeBytes = :sizeBytes, fileModifiedAt = :fileModifiedAt, lastSeenAt = :now, " +
            "missingAt = NULL, libraryFolder = :libraryFolder " +
            "WHERE sourceId = :sourceId AND path = :path"
    )
    suspend fun touch(
        sourceId: String,
        path: String,
        name: String,
        title: String,
        sortTitle: String,
        sizeBytes: Long,
        fileModifiedAt: Long,
        libraryFolder: String?,
        now: Long,
    )

    @Query(
        "SELECT * FROM file_media_items WHERE sourceId = :sourceId AND path = :path " +
            "AND (:includePrivate OR isPrivate = 0)"
    )
    suspend fun get(sourceId: String, path: String, includePrivate: Boolean): FileMediaItemEntity?

    @Query(
        "SELECT * FROM file_media_items WHERE sourceId = :sourceId AND path IN (:paths) " +
            "AND (:includePrivate OR isPrivate = 0)"
    )
    suspend fun getMany(sourceId: String, paths: List<String>, includePrivate: Boolean): List<FileMediaItemEntity>

    /** 探测结果写回（`probeState` 写过后就不再重复探测）。 */
    @Query(
        "UPDATE file_media_items SET durationMs = :durationMs, width = :width, height = :height, " +
            "videoCodec = :videoCodec, audioCodec = :audioCodec, bitrate = :bitrate, " +
            "probeState = :probeState, probedAt = :now WHERE sourceId = :sourceId AND path = :path"
    )
    suspend fun updateProbe(
        sourceId: String,
        path: String,
        durationMs: Long?,
        width: Int?,
        height: Int?,
        videoCodec: String?,
        audioCodec: String?,
        bitrate: Int?,
        probeState: String,
        now: Long,
    )

    /**
     * 档二标记：把一批文件标为隐私 / 取消隐私（`docs/隐私模式方案.md` §八「长按条目菜单」）。
     * 返回受影响行数。**不受当前模式限制** —— 隐私模式内才能看到私有条目，
     * 普通模式下 UI 拿不到这些 path，自然不会误标。
     */
    @Query("UPDATE file_media_items SET isPrivate = :isPrivate WHERE sourceId = :sourceId AND path IN (:paths)")
    suspend fun setPrivate(sourceId: String, paths: List<String>, isPrivate: Boolean): Int

    /** 这批 path 里哪些是私有的（只回 path；列举层据此在普通模式下过滤实时目录）。 */
    @Query(
        "SELECT path FROM file_media_items WHERE sourceId = :sourceId " +
            "AND path IN (:paths) AND isPrivate = 1"
    )
    suspend fun privatePaths(sourceId: String, paths: List<String>): List<String>

    /**
     * 被标为隐私的**目录**（[RoomFileCatalog.setFolderPrivate] 写入的 `kind = FOLDER` 行）。
     *
     * 递归语义由查询方实现：路径以任一目录为前缀（自身或子路径）的文件都视为私有 ——
     * 所以新落进目录的文件**不需要写继承标记**，天然被盖住。
     */
    @Query(
        "SELECT path FROM file_media_items WHERE sourceId = :sourceId " +
            "AND kind = 'FOLDER' AND isPrivate = 1"
    )
    suspend fun privateFolders(sourceId: String): List<String>

    /** 目录行整行写入（[FileMediaItemEntity] 的探测字段全为空）。 */
    @Upsert
    suspend fun upsertFolders(rows: List<FileMediaItemEntity>)

    /** 取消目录隐私 = 删掉 FOLDER 行（下次再标重新 upsert，无历史包袱）。 */
    @Query(
        "DELETE FROM file_media_items WHERE sourceId = :sourceId " +
            "AND kind = 'FOLDER' AND path IN (:paths)"
    )
    suspend fun deleteFolders(sourceId: String, paths: List<String>): Int

    /** 「最近添加」/「昨日新增」的数据源：按首次发现倒序。`kind = VIDEO` 排除目录行。 */
    @Query(
        "SELECT * FROM file_media_items WHERE sourceId = :sourceId AND missingAt IS NULL " +
            "AND kind = 'VIDEO' AND (:includePrivate OR isPrivate = 0) ORDER BY firstSeenAt DESC LIMIT :limit"
    )
    suspend fun newest(sourceId: String, limit: Int, includePrivate: Boolean): List<FileMediaItemEntity>

    /**
     * 「昨日新增」列表：按**首次发现时间**落在一段区间内（D5 口径）。
     *
     * 边界由调用方按本地时区算好再传（库里不存日期字符串，避免「跨时区同一条算两天」）。
     */
    @Query(
        "SELECT * FROM file_media_items WHERE sourceId = :sourceId AND missingAt IS NULL " +
            "AND kind = 'VIDEO' AND (:includePrivate OR isPrivate = 0) " +
            "AND firstSeenAt >= :fromMs AND firstSeenAt < :toMs ORDER BY firstSeenAt DESC"
    )
    suspend fun newlyAdded(
        sourceId: String,
        fromMs: Long,
        toMs: Long,
        includePrivate: Boolean,
    ): List<FileMediaItemEntity>

    /** 「昨日新增」统计：条数 / 字节 / 时长，按 kind 分组（本期只会返回 VIDEO）。 */
    @Query(
        "SELECT kind AS kind, COUNT(*) AS count, SUM(sizeBytes) AS bytes, " +
            "SUM(IFNULL(durationMs, 0)) AS durationMs FROM file_media_items " +
            "WHERE sourceId = :sourceId AND missingAt IS NULL AND kind = 'VIDEO' " +
            "AND (:includePrivate OR isPrivate = 0) " +
            "AND firstSeenAt >= :fromMs AND firstSeenAt < :toMs GROUP BY kind"
    )
    suspend fun newlyAddedStats(
        sourceId: String,
        fromMs: Long,
        toMs: Long,
        includePrivate: Boolean,
    ): List<NewlyAddedStatRow>

    /**
     * 全量扫描结束时标记「这轮没看见」的文件。
     *
     * 只标不删（用户 2026-10-27 ④「先一直保留」）；**扫描失败时不许调**，
     * 否则 SMB 断连会等于「整个库都没了」（§13 风险 3）。
     */
    @Query(
        "UPDATE file_media_items SET missingAt = :now WHERE sourceId = :sourceId " +
            "AND lastSeenAt < :scanStartedAt AND missingAt IS NULL AND kind != 'FOLDER'"
    )
    suspend fun markMissing(sourceId: String, scanStartedAt: Long, now: Long): Int
    // kind != 'FOLDER'：目录行不在扫描清单里（recordSeen 只登记文件），不参与「这轮没看见」判定
}

/**
 * 「昨日新增」的聚合结果（Room 投影，列名与上面的 SQL 别名一致）。
 */
data class NewlyAddedStatRow(
    val kind: String,
    val count: Int,
    val bytes: Long?,
    val durationMs: Long?,
)

/**
 * 本地播放断点（`docs/10-数据存储方案.md` §6.5）。
 *
 * 写入用「先 UPDATE 后 INSERT」而不是 `@Upsert`：整行覆盖会把 `playCount` 清零，
 * 而 `ON CONFLICT DO UPDATE` 在 minSdk 26 的 SQLite（3.18）上不支持。
 */
@Dao
interface PlaybackProgressDao {
    @Query("SELECT * FROM playback_progress WHERE sourceId = :sourceId AND mediaKey = :mediaKey")
    suspend fun get(sourceId: String, mediaKey: String): PlaybackProgressEntity?

    @Query("SELECT * FROM playback_progress WHERE sourceId = :sourceId AND mediaKey IN (:keys)")
    suspend fun getMany(sourceId: String, keys: List<String>): List<PlaybackProgressEntity>

    @Query("SELECT * FROM playback_progress WHERE sourceId = :sourceId AND mediaKey = :mediaKey")
    fun observe(sourceId: String, mediaKey: String): Flow<PlaybackProgressEntity?>

    /** 返回受影响行数；0 = 还没有这行，调用方补一条 INSERT。 */
    @Query(
        "UPDATE playback_progress SET positionMs = :positionMs, durationMs = :durationMs, " +
            "completed = :completed, lastPlayedAt = :now, updatedAt = :now " +
            "WHERE sourceId = :sourceId AND mediaKey = :mediaKey"
    )
    suspend fun update(
        sourceId: String,
        mediaKey: String,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
        now: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: PlaybackProgressEntity)

    /** 会话结束计数（`endSession` 语义）；本期只在播完时 +1。 */
    @Query(
        "UPDATE playback_progress SET playCount = playCount + 1, updatedAt = :now " +
            "WHERE sourceId = :sourceId AND mediaKey = :mediaKey"
    )
    suspend fun bumpPlayCount(sourceId: String, mediaKey: String, now: Long)

    /**
     * 「继续观看」：未看完、位置 > 0，按最后播放时间倒序。
     *
     * ⚠️ **隐私过滤不能只看 `sourceId`**（`docs/隐私模式方案.md` §三 P0#2 / §六修正）：
     * 本表只有 `(sourceId, mediaKey)`，而档二的隐私是**条目级**的 —— 同一个普通 SMB 源里
     * 被标隐私的那几个文件，进度仍会从这里被查出来。所以必须关联 `file_media_items`
     * 排除 `isPrivate = 1` 的 path（`NOT EXISTS` 用 `(sourceId, path)` 主键，走索引）。
     */
    @Query(
        "SELECT * FROM playback_progress WHERE sourceId = :sourceId AND completed = 0 " +
            "AND positionMs > 0 AND (:includePrivate OR NOT EXISTS (" +
            "SELECT 1 FROM file_media_items f WHERE f.sourceId = playback_progress.sourceId " +
            "AND f.path = playback_progress.mediaKey AND f.isPrivate = 1)) " +
            "ORDER BY lastPlayedAt DESC LIMIT :limit"
    )
    suspend fun continueWatching(
        sourceId: String,
        limit: Int,
        includePrivate: Boolean,
    ): List<PlaybackProgressEntity>

    @Query("DELETE FROM playback_progress WHERE sourceId = :sourceId AND mediaKey = :mediaKey")
    suspend fun delete(sourceId: String, mediaKey: String)
}

/** 探测出的多轨明细（`media_streams`）：一条目 1:N，整体替换（先删后插）。 */
@Dao
interface MediaStreamDao {
    @Query("DELETE FROM media_streams WHERE sourceId = :sourceId AND path = :path")
    suspend fun deleteFor(sourceId: String, path: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MediaStreamEntity>)

    @Query("SELECT * FROM media_streams WHERE sourceId = :sourceId AND path = :path ORDER BY streamIndex")
    suspend fun get(sourceId: String, path: String): List<MediaStreamEntity>
}

/**
 * 观看历史（`watch_history`）：**只追加、闭合一次**。
 *
 * 写入节奏见 `docs/10 §7.2`：起播 `beginSession` 插一行（`endedAt = null`），
 * 退页 / 播完 / 失败时 `endSession` 把它闭合。进程被杀留下的未闭合行由启动修复任务补。
 */
@Dao
interface WatchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: WatchHistoryEntity)

    /** 闭合会话（一个事务里由 `PlaybackStore.endSession` 与进度更新一起调用）。 */
    @Query(
        "UPDATE watch_history SET endedAt = :endedAt, endPositionMs = :endPositionMs, " +
            "durationMs = :durationMs, completed = :completed, endedReason = :endedReason " +
            "WHERE sessionId = :sessionId AND endedAt IS NULL"
    )
    suspend fun close(
        sessionId: String,
        endedAt: Long,
        endPositionMs: Long,
        durationMs: Long,
        completed: Boolean,
        endedReason: String,
    ): Int

    /** 未闭合会话（进程被杀 / 被系统回收）：只取最近的，更早的直接标 UNKNOWN，不逐条修。 */
    @Query("SELECT * FROM watch_history WHERE endedAt IS NULL AND startedAt >= :sinceMs")
    suspend fun openSessions(sinceMs: Long): List<WatchHistoryEntity>

    @Query("UPDATE watch_history SET endedAt = :at, endedReason = 'UNKNOWN' WHERE endedAt IS NULL AND startedAt < :sinceMs")
    suspend fun closeStaleSessions(sinceMs: Long, at: Long): Int

    /**
     * 「最近看过」：**按条目去重**，每条取最近一次会话。
     *
     * 用「先取每个 mediaKey 的 MAX(startedAt) 再回连」而不是 `GROUP BY` + 聚合列：
     * 后者在 SQLite 上取非分组列（如 titleSnapshot）行为不确定。
     *
     * 隐私过滤同 [PlaybackProgressDao.continueWatching]：本表只有 `mediaKey`，
     * 档二隐私必须关联 `file_media_items` 才能排除（否则退出隐私后仍能从历史漏出私文档标题）。
     */
    @Query(
        "SELECT h.* FROM watch_history h INNER JOIN (" +
            "SELECT mediaKey, MAX(startedAt) AS lastAt FROM watch_history " +
            "WHERE sourceId = :sourceId GROUP BY mediaKey) m " +
            "ON h.mediaKey = m.mediaKey AND h.startedAt = m.lastAt " +
            "WHERE h.sourceId = :sourceId AND (:includePrivate OR NOT EXISTS (" +
            "SELECT 1 FROM file_media_items f WHERE f.sourceId = h.sourceId " +
            "AND f.path = h.mediaKey AND f.isPrivate = 1)) " +
            "ORDER BY h.startedAt DESC LIMIT :limit"
    )
    suspend fun recentByMedia(
        sourceId: String,
        limit: Int,
        includePrivate: Boolean,
    ): List<WatchHistoryEntity>

    /** 某条目的会话记录（详情页「看过 N 次 / 上次看到」用）。 */
    @Query(
        "SELECT * FROM watch_history WHERE sourceId = :sourceId AND mediaKey = :mediaKey " +
            "ORDER BY startedAt DESC LIMIT :limit"
    )
    suspend fun sessionsOf(sourceId: String, mediaKey: String, limit: Int): List<WatchHistoryEntity>

    @Query("DELETE FROM watch_history WHERE sourceId = :sourceId AND mediaKey = :mediaKey")
    suspend fun deleteFor(sourceId: String, mediaKey: String)
}
