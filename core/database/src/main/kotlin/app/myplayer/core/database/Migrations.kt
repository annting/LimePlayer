package app.myplayer.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2：**纯增量**（新增 3 张表 + 索引），不搬迁、不改动任何既有列。
 * `file_media_items` 存文件条目与探测汇总，`playback_progress` 存本地断点，
 * `media_streams` 存多轨明细（详情页「取一次存库」靠它才完整）。
 *
 * 为什么必须写（`docs/10-数据存储方案.md` F1）：`AppModule` 的 builder 没有
 * `fallbackToDestructiveMigration()`，缺迁移会在覆盖安装后启动时直接
 * `IllegalStateException: A migration from 1 to 2 was required but not found`。
 *
 * 下面的 SQL **必须与 `schemas/…/2.json` 里的 `createSql` 逐字一致**：
 * Room 在 `verifyMigrations` / 打开数据库时按 schema 指纹校验，差一个反引号都会报
 * 「Migration didn't properly handle…」。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `file_media_items` (" +
                "`sourceId` TEXT NOT NULL, `path` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `title` TEXT NOT NULL, `sortTitle` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `fileModifiedAt` INTEGER NOT NULL, " +
                "`firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, " +
                "`missingAt` INTEGER, `libraryFolder` TEXT, `durationMs` INTEGER, " +
                "`width` INTEGER, `height` INTEGER, `videoCodec` TEXT, `audioCodec` TEXT, " +
                "`bitrate` INTEGER, `thumbPath` TEXT, `probeState` TEXT NOT NULL, " +
                "`probedAt` INTEGER, PRIMARY KEY(`sourceId`, `path`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_file_media_items_sourceId_firstSeenAt` " +
                "ON `file_media_items` (`sourceId`, `firstSeenAt`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_file_media_items_sourceId_libraryFolder_sortTitle` " +
                "ON `file_media_items` (`sourceId`, `libraryFolder`, `sortTitle`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_file_media_items_probeState` " +
                "ON `file_media_items` (`probeState`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_file_media_items_missingAt` " +
                "ON `file_media_items` (`missingAt`)"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playback_progress` (" +
                "`sourceId` TEXT NOT NULL, `mediaKey` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL, " +
                "`playCount` INTEGER NOT NULL, `lastPlayedAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `lastSessionId` TEXT, " +
                "PRIMARY KEY(`sourceId`, `mediaKey`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_playback_progress_completed_lastPlayedAt` " +
                "ON `playback_progress` (`completed`, `lastPlayedAt`)"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `media_streams` (" +
                "`sourceId` TEXT NOT NULL, `path` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`streamIndex` INTEGER NOT NULL, `codec` TEXT, `language` TEXT, " +
                "`displayTitle` TEXT, `width` INTEGER, `height` INTEGER, `frameRate` REAL, " +
                "`bitDepth` INTEGER, `channels` INTEGER, `sampleRate` INTEGER, `bitrate` INTEGER, " +
                "`isExternal` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, " +
                "PRIMARY KEY(`sourceId`, `path`, `kind`, `streamIndex`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_streams_sourceId_path` " +
                "ON `media_streams` (`sourceId`, `path`)"
        )
    }
}

/**
 * v2 → v3：新增 `watch_history`（观看历史，append-only 事实表）。
 *
 * 同样是纯增量。注意 **v2 已经发布给测试机**，所以这个迁移不能改 v2 的内容 ——
 * 只能往上加表（真机走的是 1→2→3 连续迁移）。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `watch_history` (" +
                "`sessionId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `mediaKey` TEXT NOT NULL, " +
                "`titleSnapshot` TEXT NOT NULL, `kind` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                "`endedAt` INTEGER, `startPositionMs` INTEGER NOT NULL, `endPositionMs` INTEGER, " +
                "`durationMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL, " +
                "`playMethod` TEXT, `endedReason` TEXT, PRIMARY KEY(`sessionId`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_watch_history_startedAt` " +
                "ON `watch_history` (`startedAt`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_watch_history_sourceId_mediaKey_startedAt` " +
                "ON `watch_history` (`sourceId`, `mediaKey`, `startedAt`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_watch_history_endedAt` " +
                "ON `watch_history` (`endedAt`)"
        )
    }
}

/**
 * v3 → v4：`file_media_items` 增加 `isPrivate`（**档二 · 文档级隐私**，
 * `docs/隐私模式方案.md` §三 / §八）。
 *
 * 纯增量：`DEFAULT 0` 让旧库里的每一条既有文件都按「可见」处理，**不需要回填**。
 * 索引 `(sourceId, isPrivate)` 是过滤查询的主形状（`WHERE sourceId = ? AND isPrivate = 0`），
 * 既有索引 `(sourceId, firstSeenAt)` 帮不上 `isPrivate` 的等值条件。
 *
 * ⚠️ v2 / v3 已经发布给测试机，这里只能往 `file_media_items` 上加列，
 * **不能**改 `MIGRATION_1_2` / `MIGRATION_2_3` 里的任何 SQL（真机走 1→2→3→4 连续迁移）。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `file_media_items` ADD COLUMN `isPrivate` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_file_media_items_sourceId_isPrivate` " +
                "ON `file_media_items` (`sourceId`, `isPrivate`)"
        )
    }
}
