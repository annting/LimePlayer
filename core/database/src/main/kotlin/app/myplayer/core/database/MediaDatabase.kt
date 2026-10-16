package app.myplayer.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [LibraryEntity::class, MediaItemEntity::class],
    version = 1,
    // M1 未发布、无历史数据，暂不导出 schema；
    // 首次发版前（见 04 开发计划 P5）改回 true 并接入 ksp arg("room.schemaLocation")，为迁移建立基线。
    exportSchema = false,
)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun mediaItemDao(): MediaItemDao
}
