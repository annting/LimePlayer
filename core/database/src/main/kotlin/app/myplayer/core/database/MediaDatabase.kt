package app.myplayer.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        LibraryEntity::class,
        MediaItemEntity::class,
        FileMediaItemEntity::class,
        PlaybackProgressEntity::class,
        MediaStreamEntity::class,
        WatchHistoryEntity::class,
    ],
    version = 4,
    // 导出 schema 是为了给 v1→v2 / v2→v3 / v3→v4 迁移建基线（docs/10-数据存储方案.md §9）：
    // 没有 schemas/…/N.json，MigrationTestHelper 无法验证迁移。
    exportSchema = true,
)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun fileMediaItemDao(): FileMediaItemDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun mediaStreamDao(): MediaStreamDao
    abstract fun watchHistoryDao(): WatchHistoryDao
}
