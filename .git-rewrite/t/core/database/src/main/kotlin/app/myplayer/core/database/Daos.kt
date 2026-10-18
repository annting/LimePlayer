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
}
