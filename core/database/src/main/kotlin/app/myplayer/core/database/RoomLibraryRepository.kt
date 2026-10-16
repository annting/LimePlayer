package app.myplayer.core.database

import app.myplayer.core.model.ItemQuery
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary
import app.myplayer.core.model.MediaSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLibraryRepository(private val db: MediaDatabase) : LibraryRepository {

    override fun observeLibraries(serverId: String): Flow<List<MediaLibrary>> =
        db.libraryDao().observe(serverId).map { rows -> rows.map { it.toModel() } }

    override fun observeItems(serverId: String, libraryId: String): Flow<List<MediaItemDto>> =
        db.mediaItemDao().observeLibrary(serverId, libraryId).map { rows -> rows.map { it.toDto() } }

    override suspend fun syncLibraries(source: MediaSource) {
        val libs = source.getLibraries()
        db.libraryDao().upsertAll(libs.map { it.toEntity(source.serverId) })
    }

    /**
     * 分页同步（docs/02-技术规范.md §7.2）：每页 200 条。
     * 顺序必须是「**先处理空页 → 再累加偏移 → 最后判 hasMore**」——
     * 服务端 `TotalRecordCount` 偏大时，空页会让循环立即停止，不会空转。
     */
    override suspend fun syncItems(source: MediaSource, libraryId: String) {
        var start = 0
        val pageSize = 200
        while (true) {
            val page = source.getItems(
                ItemQuery(libraryId = libraryId, startIndex = start, limit = pageSize)
            )
            if (page.items.isEmpty()) break
            db.mediaItemDao().upsertAll(page.items.map { it.toEntity() })
            start += page.items.size
            if (!page.hasMore) break
        }
    }
}
