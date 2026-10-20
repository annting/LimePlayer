package app.myplayer.core.database

import androidx.room.Dao
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
